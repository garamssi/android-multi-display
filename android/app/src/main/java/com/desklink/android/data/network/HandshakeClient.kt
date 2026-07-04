package com.desklink.android.data.network

import android.os.Build
import com.desklink.android.domain.model.ConnectionError
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.ProtocolConstants
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

/**
 * Handles the client-side handshake protocol with the Mac server.
 */
class HandshakeClient @Inject constructor() {

    fun buildHandshakeRequest(
        screenWidth: Int,
        screenHeight: Int,
        maxFps: Int = 120,
    ): ByteArray {
        val json = JSONObject().apply {
            put("protocolVersion", ProtocolConstants.PROTOCOL_VERSION)
            put("clientName", "DeskLink Android")
            put("clientVersion", "1.0.0")
            put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("osVersion", "Android ${Build.VERSION.RELEASE}")
            put("screenWidth", screenWidth)
            put("screenHeight", screenHeight)
            put("maxFps", maxFps)
            put("supportedCodecs", JSONArray(listOf("hevc", "h264")))
            put("touchSupport", true)
            put("multiTouchMaxPoints", 10)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Parses a HANDSHAKE_RESPONSE payload (A-L7).
     *
     * Validates the server's `protocolVersion` against [ProtocolConstants.PROTOCOL_VERSION]
     * (returns [HandshakeResult.Failed] with [ConnectionError.PROTOCOL_MISMATCH] on a
     * mismatch) and wraps JSON parsing so a malformed body yields a typed
     * [HandshakeResult.Failed] instead of leaking a [JSONException] upstream (which
     * previously surfaced as a misleading TIMEOUT).
     */
    fun parseHandshakeResponse(payload: ByteArray): HandshakeResult {
        val json = try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: JSONException) {
            return HandshakeResult.Failed(ConnectionError.CONFIG_NEGOTIATION_FAILED)
        }

        // Protocol version must match. Absent -> assume current for backward compat.
        val serverVersion = json.optInt("protocolVersion", ProtocolConstants.PROTOCOL_VERSION)
        if (serverVersion != ProtocolConstants.PROTOCOL_VERSION) {
            return HandshakeResult.Failed(ConnectionError.PROTOCOL_MISMATCH)
        }

        val accepted = json.optBoolean("accepted", false)
        if (!accepted) {
            val reason = json.optString("rejectReason", "Unknown reason")
            return HandshakeResult.Rejected(reason)
        }
        return HandshakeResult.Accepted(
            serverName = json.optString("serverName", "Unknown"),
            serverVersion = json.optString("serverVersion", "0.0.0"),
        )
    }

    fun buildConfigRequest(config: DisplayConfig): ByteArray {
        val json = JSONObject().apply {
            put("width", config.width)
            put("height", config.height)
            put("fps", config.fps)
            put("codec", if (config.codec == DisplayConfig.Codec.HEVC) "hevc" else "h264")
            put("bitrateKbps", config.bitrateKbps)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Parses a CONFIG_RESPONSE payload. Returns null on malformed JSON or a rejected
     * negotiation (A-L7: JSON errors no longer leak as exceptions).
     */
    fun parseConfigResponse(payload: ByteArray): DisplayConfig? {
        val json = try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: JSONException) {
            return null
        }
        if (!json.optBoolean("accepted", false)) return null

        val codecStr = json.optString("codec", "hevc")
        val codec = if (codecStr == "h264") DisplayConfig.Codec.H264 else DisplayConfig.Codec.HEVC

        return DisplayConfig(
            width = json.optInt("width", DisplayConfig().width),
            height = json.optInt("height", DisplayConfig().height),
            fps = json.optInt("fps", 60),
            codec = codec,
            bitrateKbps = json.optInt("bitrateKbps", 20_000),
            keyframeInterval = json.optInt("keyframeInterval", 2),
        )
    }

    sealed interface HandshakeResult {
        data class Accepted(val serverName: String, val serverVersion: String) : HandshakeResult
        data class Rejected(val reason: String) : HandshakeResult
        data class Failed(val error: ConnectionError) : HandshakeResult
    }
}
