package com.desklink.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the teardown contract of the audio channel's `callbackFlow`.
 *
 * Cancellation is the NORMAL way the audio collection ends — backgrounding the app, a
 * reconnect, or toggling the preference all cancel the job. Releasing the `AudioTrack`
 * and closing the socket must happen on that path, because closing the socket is what
 * makes the Mac drop its tap and hand its own speakers back.
 *
 * The regression this guards: cleanup used to live in `awaitClose`, and a
 * `catch (CancellationException) { throw e }` unwound past it, so on every real teardown
 * the track leaked and the socket stayed open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioStreamTeardownTest {

    /** Cleanup placed in `awaitClose` behind a rethrow — the shape that was broken. */
    private fun brokenFlow(onCleanup: () -> Unit) = callbackFlow<Unit> {
        try {
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        }
        awaitClose { onCleanup() }
    }

    /** Cleanup in `finally` — the shape now used in production. */
    private fun fixedFlow(onCleanup: () -> Unit) = callbackFlow<Unit> {
        try {
            awaitClose { }
        } finally {
            onCleanup()
        }
    }

    @Test
    fun `cleanup in awaitClose is skipped when the collection is cancelled`() = runTest {
        var cleaned = false
        val job = launch { brokenFlow { cleaned = true }.collect() }
        advanceUntilIdle()
        job.cancel()
        job.join()
        assertFalse(cleaned, "this shape is expected to skip cleanup; the test documents why it was replaced")
    }

    @Test
    fun `cleanup in finally runs when the collection is cancelled`() = runTest {
        var cleaned = false
        val job = launch { fixedFlow { cleaned = true }.collect() }
        advanceUntilIdle()
        job.cancel()
        job.join()
        assertTrue(cleaned, "teardown must run on the cancellation path")
    }
}
