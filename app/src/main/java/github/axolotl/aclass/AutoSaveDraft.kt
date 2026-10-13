package github.axolotl.aclass

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AutoSaveState<T>(val value: T, val error: String? = null, val saving: Boolean = false, val canRetry: Boolean = false)

/** Keeps edits alive after their UI closes. Disk writes are never cancelled by a newer edit. */
class AutoSaveDraft<T>(
    initial: T,
    private val scope: CoroutineScope,
    private val validate: (T) -> String? = { null },
    private val write: suspend (T) -> Unit,
    private val detachedFailure: (String, () -> Unit) -> Unit = { _, _ -> },
) {
    private val mutableState = MutableStateFlow(AutoSaveState(initial))
    val state: StateFlow<AutoSaveState<T>> = mutableState
    private var committed = initial
    private var revision = 0L
    private var pending = false
    private var ready = false
    private var delayJob: Job? = null
    private var writing = false
    private var attachments = 0
    private var failure: String? = null
    private var notifiedRevision = -1L
    private var writeOverride: (suspend (T) -> Unit)? = null

    fun change(value: T, immediate: Boolean = false, force: Boolean = false) {
        enqueue(value, immediate, force, null)
    }

    fun restore(value: T, persist: suspend () -> Unit) {
        enqueue(value, immediate = true, force = true) { persist() }
    }

    private fun enqueue(value: T, immediate: Boolean, force: Boolean, persist: (suspend (T) -> Unit)?) {
        revision++
        delayJob?.cancel()
        failure = null
        writeOverride = persist
        val error = validate(value)
        mutableState.value = AutoSaveState(value, error, writing)
        pending = error == null && (force || value != committed || writing)
        ready = false
        if (!pending) return
        if (immediate) flush() else delayJob = scope.launch { delay(400); flush() }
    }

    fun flush() {
        delayJob?.cancel()
        delayJob = null
        if (!pending) return
        ready = true
        drain()
    }

    fun retry() {
        if (validate(state.value.value) != null) return
        revision++
        failure = null
        mutableState.value = state.value.copy(error = null, canRetry = false)
        pending = true
        flush()
    }

    fun attach() { attachments++ }
    fun refreshInitial(value: T) {
        if (revision == 0L && !writing) {
            committed = value
            mutableState.value = AutoSaveState(value)
        }
    }
    fun detach() {
        attachments = (attachments - 1).coerceAtLeast(0)
        flush()
        notifyDetachedFailure()
    }

    private fun notifyDetachedFailure() {
        val error = failure ?: return
        if (attachments == 0 && notifiedRevision != revision) {
            notifiedRevision = revision
            detachedFailure(error, ::retry)
        }
    }

    private fun drain() {
        if (writing || !pending || !ready) return
        val value = state.value.value
        val submittedRevision = revision
        val persist = writeOverride ?: write
        pending = false
        ready = false
        writing = true
        mutableState.value = state.value.copy(saving = true)
        scope.launch {
            try {
                persist(value)
                committed = value
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (submittedRevision == revision) {
                    failure = error.message ?: "保存失败，请重试"
                    mutableState.value = state.value.copy(error = failure, canRetry = true)
                    notifyDetachedFailure()
                }
            } finally {
                writing = false
                mutableState.value = state.value.copy(saving = false)
            }
            drain()
        }
    }
}
