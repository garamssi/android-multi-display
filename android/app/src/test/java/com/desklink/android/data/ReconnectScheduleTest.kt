package com.desklink.android.data

import com.desklink.android.domain.model.ProtocolConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The schedule replaced a flat one-second wait. The flat wait cost a full second on the
// common case (the Mac restarting its session, back in a few hundred milliseconds) while
// giving no extra room to the rare case of a device that is slow to re-enumerate.
class ReconnectScheduleTest {

    @Test
    fun `first retry comes fast enough for a server that is already back`() {
        // A mode-change restart tears down and rebinds in about 200 ms, so waiting a full
        // second to try once is most of the delay the user sees.
        assertTrue(
            ProtocolConstants.RECONNECT_DELAYS_MS.first() <= 250L,
            "first retry waits ${ProtocolConstants.RECONNECT_DELAYS_MS.first()} ms",
        )
    }

    @Test
    fun `the total window is not shorter than the flat schedule it replaced`() {
        // 5 attempts one second apart. Backing off faster must not buy speed by giving up
        // sooner on a device that takes several seconds to come back.
        assertTrue(
            ProtocolConstants.RECONNECT_DELAYS_MS.sum() >= 5_000L,
            "total window is ${ProtocolConstants.RECONNECT_DELAYS_MS.sum()} ms",
        )
    }

    @Test
    fun `delays only grow`() {
        val delays = ProtocolConstants.RECONNECT_DELAYS_MS
        // A schedule that dips would retry harder over time.
        assertEquals(delays.sorted(), delays)
    }

    @Test
    fun `attempt count is derived from the schedule`() {
        assertEquals(ProtocolConstants.RECONNECT_DELAYS_MS.size, ProtocolConstants.RECONNECT_MAX_ATTEMPTS)
    }
}
