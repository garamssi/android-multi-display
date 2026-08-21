package com.desklink.android.data

import com.desklink.android.data.network.HandshakeClient
import com.desklink.android.domain.model.DisplayMode
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The server announces the display mode in the handshake.
 *
 * The client needs it for two things: it must not open the input channel in mirror mode
 * (the Mac does not even bind that port), and it has to show that touch is off — an
 * unresponsive screen with no explanation reads as a broken app.
 */
class DisplayModeHandshakeTest {

    private val client = HandshakeClient()

    private fun response(vararg pairs: Pair<String, Any>): ByteArray {
        val json = JSONObject().apply {
            put("protocolVersion", 1)
            put("accepted", true)
            put("serverName", "DeskLink Mac")
            put("serverVersion", "1.0.0")
            pairs.forEach { (k, v) -> put(k, v) }
        }
        return json.toString().toByteArray()
    }

    @Test
    fun `reads mirror mode from the response`() {
        val result = client.parseHandshakeResponse(response("displayMode" to "mirror"))
        assertEquals(DisplayMode.MIRROR, (result as HandshakeClient.HandshakeResult.Accepted).displayMode)
    }

    @Test
    fun `reads extend mode from the response`() {
        val result = client.parseHandshakeResponse(response("displayMode" to "extend"))
        assertEquals(DisplayMode.EXTEND, (result as HandshakeClient.HandshakeResult.Accepted).displayMode)
    }

    /**
     * An older server sends no mode. Assuming EXTEND keeps that case working exactly as
     * before rather than silently disabling touch.
     */
    @Test
    fun `defaults to extend when the server does not say`() {
        val result = client.parseHandshakeResponse(response())
        assertEquals(DisplayMode.EXTEND, (result as HandshakeClient.HandshakeResult.Accepted).displayMode)
    }

    /**
     * An unrecognised value must not disable touch by accident, nor invent a third mode.
     */
    @Test
    fun `falls back to extend on an unknown value`() {
        val result = client.parseHandshakeResponse(response("displayMode" to "hologram"))
        assertEquals(DisplayMode.EXTEND, (result as HandshakeClient.HandshakeResult.Accepted).displayMode)
    }

    /** Wire values are fixed by the protocol and shared with the Mac. */
    @Test
    fun `wire values match the protocol`() {
        assertEquals("extend", DisplayMode.EXTEND.wireValue)
        assertEquals("mirror", DisplayMode.MIRROR.wireValue)
        assertEquals(DisplayMode.MIRROR, DisplayMode.fromWire("mirror"))
        assertEquals(DisplayMode.EXTEND, DisplayMode.fromWire("extend"))
        assertEquals(null, DisplayMode.fromWire("other"))
    }

    /** Only mirror refuses input; that single fact drives the client's behaviour. */
    @Test
    fun `only mirror refuses input`() {
        assertEquals(true, DisplayMode.EXTEND.acceptsInput)
        assertEquals(false, DisplayMode.MIRROR.acceptsInput)
    }
}
