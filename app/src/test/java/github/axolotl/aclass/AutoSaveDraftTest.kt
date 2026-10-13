package github.axolotl.aclass

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AutoSaveDraftTest {
    @Test fun editsDuringWriteCoalesceWithoutCancellingPersistenceOrResettingDraft() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val draft = AutoSaveDraft("initial", this, write = { value ->
            if (value == "first") gate.await()
            writes += value
        })
        draft.change("first", immediate = true)
        runCurrent()
        draft.change("second")
        advanceTimeBy(200)
        draft.change("last")
        advanceTimeBy(400)
        runCurrent()
        assertTrue(draft.state.value.saving)
        assertEquals("last", draft.state.value.value)
        assertTrue(writes.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "last"), writes)
        assertEquals("last", draft.state.value.value)
        assertFalse(draft.state.value.saving)
    }

    @Test fun invalidInputCancelsPendingValueAndKeepsLastPersistedValue() = runTest {
        var stored = "10"
        val draft = AutoSaveDraft("10", this, validate = {
            if (it.toIntOrNull() in 0..60) null else "invalid"
        }, write = { stored = it })
        draft.change("20")
        advanceTimeBy(200)
        draft.change("61")
        draft.flush()
        advanceUntilIdle()
        assertEquals("10", stored)
        assertEquals("61", draft.state.value.value)
        assertEquals("invalid", draft.state.value.error)
        assertFalse(draft.state.value.canRetry)
        draft.change("30")
        advanceTimeBy(400)
        runCurrent()
        assertEquals("30", stored)
    }

    @Test fun closingFlushesAndDetachedFailureOffersRetryWithoutLosingInput() = runTest {
        var fail = true
        var stored = "initial"
        val notifications = mutableListOf<String>()
        var retry: (() -> Unit)? = null
        val draft = AutoSaveDraft("initial", this, write = {
            if (fail) throw IOException("disk full")
            stored = it
        }, detachedFailure = { error, action -> notifications += error; retry = action })
        draft.attach()
        draft.change("latest")
        draft.detach()
        runCurrent()
        assertEquals(listOf("disk full"), notifications)
        assertEquals("latest", draft.state.value.value)
        assertEquals("initial", stored)
        retry!!()
        runCurrent()
        assertEquals(listOf("disk full", "disk full"), notifications)
        fail = false
        retry!!()
        runCurrent()
        assertEquals("latest", stored)
        assertNull(draft.state.value.error)
    }

    @Test fun staleFailureDoesNotReplaceNewerInputOrError() = runTest {
        val gate = CompletableDeferred<Unit>()
        var stored = "initial"
        val draft = AutoSaveDraft("initial", this, write = {
            if (it == "first") { gate.await(); throw IOException("stale failure") }
            stored = it
        })
        draft.attach()
        draft.change("first", immediate = true)
        runCurrent()
        draft.change("latest", immediate = true)
        gate.complete(Unit)
        runCurrent()
        assertEquals("latest", stored)
        assertEquals("latest", draft.state.value.value)
        assertNull(draft.state.value.error)
    }

    @Test fun restoreWaitsForInFlightWriteAndNewerTypingWinsOverRestore() = runTest {
        val gate = CompletableDeferred<Unit>()
        val restoreGate = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val draft = AutoSaveDraft("initial", this, write = {
            if (it == "first") gate.await()
            writes += it
        })
        draft.change("first", immediate = true)
        runCurrent()
        draft.restore("default") { restoreGate.await(); writes += "restore" }
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("first"), writes)
        assertEquals("default", draft.state.value.value)
        draft.change("latest", immediate = true)
        restoreGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "restore", "latest"), writes)
        assertEquals("latest", draft.state.value.value)
    }
}
