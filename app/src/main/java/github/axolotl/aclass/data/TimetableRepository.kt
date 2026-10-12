package github.axolotl.aclass.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

sealed interface RefreshResult {
    data object Success : RefreshResult
    data class Failure(val message: String) : RefreshResult
    data object NoAccount : RefreshResult
}

class TimetableRepository(
    private val storage: TimetableStorage,
    private val gateway: SchoolGateway,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = schoolClock(),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val ready = CompletableDeferred<Unit>()
    private val gate = Mutex()
    private val writes = Mutex()
    private var inFlight: Deferred<RefreshResult>? = null
    private var inFlightAccount: AccountSettings? = null

    init {
        scope.launch {
            try {
                _state.value = AppState(storage.loadSnapshot(), storage.loadSettings(), storage.loadAccount(), loading = false)
            } finally { ready.complete(Unit); _state.update { it.copy(loading = false) } }
        }
    }

    fun refreshAutomatically() { scope.launch { refresh() } }

    /** Concurrent callers join the same request; different accounts wait for the previous request. */
    suspend fun refresh(overrideAccount: AccountSettings? = null): RefreshResult {
        ready.await()
        val account = overrideAccount ?: state.value.account ?: return RefreshResult.NoAccount
        if (account.studentId.isBlank() || account.password.isEmpty() || !validTerm(account.term)) {
            return RefreshResult.Failure("请填写学号、密码和有效学期")
        }
        while (true) {
            val (task, matches) = gate.withLock {
                val existing = inFlight?.takeUnless { it.isCompleted }
                if (existing != null) existing to (inFlightAccount == account)
                else {
                    val next = scope.async { performRefresh(account, overrideAccount != null) }
                    inFlight = next
                    inFlightAccount = account
                    next to true
                }
            }
            val result = task.await()
            if (matches) return result
        }
    }

    private suspend fun performRefresh(account: AccountSettings, saveAccount: Boolean): RefreshResult {
        _state.update { it.copy(refreshing = true) }
        try {
            val courses = gateway.fetch(account)
            currentCoroutineContext().ensureActive()
            val snapshot = TimetableSnapshot(account.studentId, account.term, clock.millis(), courses)
            writes.withLock {
                currentCoroutineContext().ensureActive()
                val oldAccount = state.value.account
                try {
                    if (saveAccount) storage.saveAccount(account)
                    storage.saveSnapshot(snapshot)
                } catch (error: Exception) {
                    if (saveAccount) runCatching { storage.saveAccount(oldAccount) }
                    throw error
                }
                _state.update { it.copy(snapshot = snapshot, account = if (saveAccount) account else it.account) }
            }
            return RefreshResult.Success
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            return RefreshResult.Failure(error.message?.take(180) ?: "刷新失败，请检查网络后重试")
        } finally { _state.update { it.copy(refreshing = false) } }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        ready.await()
        withContext(dispatcher) {
            writes.withLock {
                val settings = transform(state.value.settings)
                storage.saveSettings(settings)
                _state.update { it.copy(settings = settings) }
            }
        }
    }

    suspend fun clearAccount() {
        ready.await()
        gate.withLock { inFlight?.cancel(); inFlight = null; inFlightAccount = null }
        withContext(dispatcher) {
            writes.withLock { storage.saveAccount(null); _state.update { it.copy(account = null) } }
        }
    }

    fun close() { scope.cancel() }
}
