package com.desklink.android.domain.model

object MessageType {
    // Control channel (0x01-0x0F)
    const val HANDSHAKE_REQUEST: Byte = 0x01
    const val HANDSHAKE_RESPONSE: Byte = 0x02
    const val CONFIG_REQUEST: Byte = 0x03
    const val CONFIG_RESPONSE: Byte = 0x04
    const val START_STREAM: Byte = 0x05
    const val STOP_STREAM: Byte = 0x06
    const val PING: Byte = 0x07
    const val PONG: Byte = 0x08
    const val ERROR: Byte = 0x09
    const val DISCONNECT: Byte = 0x0A
    const val BITRATE_UPDATE: Byte = 0x0B
    const val CONFIG_UPDATE: Byte = 0x0C
    // LAN mutual authentication (P3): challenge-response keyed by HKDF(PIN), inside TLS.
    const val AUTH_CHALLENGE: Byte = 0x0D
    const val AUTH_RESPONSE: Byte = 0x0E
    const val AUTH_CONFIRM: Byte = 0x0F

    // Video channel (0x10-0x1F)
    const val VIDEO_FRAME: Byte = 0x10
    const val VIDEO_CONFIG: Byte = 0x11
    const val KEYFRAME_REQUEST: Byte = 0x12

    // Input channel (0x20-0x2F)
    const val TOUCH_EVENT: Byte = 0x20
    const val TOUCH_BATCH: Byte = 0x21
    const val SCROLL: Byte = 0x22
    const val POINTER_BUTTON: Byte = 0x23
}
