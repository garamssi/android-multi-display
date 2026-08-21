package com.desklink.android.data.network

import android.os.Build
import com.desklink.android.domain.model.ConnectionError
import com.desklink.android.domain.model.DisplayMode
import com.desklink.android.domain.model.VideoScaling
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.ProtocolConstants
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

class HandshakeClient @Inject constructor() {

    fun buildHandshakeRequest(
        screenWidth: Int,
        screenHeight: Int,
        // No default: a fixed value here was never overridden, so every device advertised
        // the same capability and the server's fps cap became inert.
        maxFps: Int,
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

    fun parseHandshakeResponse(payload: ByteArray): HandshakeResult {
        val json = try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: JSONException) {
            return HandshakeResult.Failed(ConnectionError.CONFIG_NEGOTIATION_FAILED)
        }

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
            displayMode = DisplayMode.fromWire(json.optString("displayMode")) ?: DisplayMode.DEFAULT,
            videoScaling = VideoScaling.fromWire(json.optString("videoScaling")),
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

    /// The code carried by an ERROR frame, or null if it does not name one.
    ///
    /// The distinction matters: a rejected proof asks for a new code, a lockout asks the user
    /// to wait, and treating them alike sends them to re-read a PIN that was never the problem.
    fun parseErrorCode(payload: ByteArray): ConnectionError? {
        val code = runCatching {
            JSONObject(String(payload, Charsets.UTF_8)).optInt("code", -1)
        }.getOrDefault(-1)
        return ConnectionError.fromCode(code)
    }

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
        data class Accepted(
            val serverName: String,
            val serverVersion: String,
            val displayMode: DisplayMode = DisplayMode.DEFAULT,
            val videoScaling: VideoScaling = VideoScaling.DEFAULT,
        ) : HandshakeResult
        data class Rejected(val reason: String) : HandshakeResult
        data class Failed(val error: ConnectionError) : HandshakeResult
    }
}
