package com.desklink.android.data

import com.desklink.android.data.network.HandshakeClient
import com.desklink.android.domain.model.VideoScaling
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// The Mac owns the choice and states it in the handshake, the same way it states the display
// mode. A server that does not name it must not silently crop.
class VideoScalingHandshakeTest {

    private fun accepted(json: String) =
        HandshakeClient().parseHandshakeResponse(json.toByteArray())
            as HandshakeClient.HandshakeResult.Accepted

    @Test
    fun `fill is carried through`() {
        val result = accepted("""{"accepted":true,"serverName":"Mac","videoScaling":"fill"}""")
        assertEquals(VideoScaling.FILL, result.videoScaling)
    }

    @Test
    fun `fit is carried through`() {
        val result = accepted("""{"accepted":true,"serverName":"Mac","videoScaling":"fit"}""")
        assertEquals(VideoScaling.FIT, result.videoScaling)
    }

    @Test
    fun `a server that names nothing shows the whole picture`() {
        val result = accepted("""{"accepted":true,"serverName":"Mac"}""")
        assertEquals(VideoScaling.FIT, result.videoScaling)
    }

    // Cropping on a value this client does not understand would hide part of the screen for a
    // reason the user cannot see.
    @Test
    fun `an unknown value shows the whole picture`() {
        val result = accepted("""{"accepted":true,"serverName":"Mac","videoScaling":"cover"}""")
        assertEquals(VideoScaling.FIT, result.videoScaling)
    }
}
