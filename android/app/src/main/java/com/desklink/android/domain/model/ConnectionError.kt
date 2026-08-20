package com.desklink.android.domain.model

enum class ConnectionError(val code: Int, val description: String) {
    REFUSED(1000, "Server refused connection"),
    PROTOCOL_MISMATCH(1001, "Protocol version mismatch"),
    TIMEOUT(1002, "Connection timed out"),
    LOST(1003, "Connection lost"),
    PAIRING_REJECTED(1004, "Pairing rejected (wrong PIN)"),

    ENCODER_INIT_FAILED(1100, "Encoder initialization failed"),
    ENCODER_FAILED(1101, "Encoding error"),
    DECODER_INIT_FAILED(1102, "Decoder initialization failed"),
    DECODER_FAILED(1103, "Decoding error"),
    CODEC_NOT_SUPPORTED(1104, "Codec not supported"),

    DISPLAY_CREATE_FAILED(1200, "Virtual display creation failed"),
    DISPLAY_CAPTURE_FAILED(1201, "Screen capture failed"),
    DISPLAY_RESOLUTION_INVALID(1202, "Invalid resolution"),

    INPUT_INJECTION_FAILED(1300, "Input injection failed"),
    INPUT_PERMISSION_DENIED(1301, "Input permission denied"),

    CONFIG_INVALID(1400, "Invalid configuration"),
    CONFIG_NEGOTIATION_FAILED(1401, "Config negotiation failed");

    companion object {
        fun fromCode(code: Int): ConnectionError? = entries.find { it.code == code }
    }
}
