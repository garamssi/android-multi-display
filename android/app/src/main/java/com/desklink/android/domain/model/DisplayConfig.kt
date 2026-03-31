package com.desklink.android.domain.model

data class DisplayConfig(
    val width: Int = 1920,
    val height: Int = 1200,
    val fps: Int = 60,
    val codec: Codec = Codec.HEVC,
    val bitrateKbps: Int = 20_000,
    val keyframeInterval: Int = 2,
) {
    enum class Codec(val id: Byte) {
        HEVC(0x01),
        H264(0x02);

        companion object {
            fun fromId(id: Byte): Codec =
                entries.firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException("Unknown codec id: $id")
        }
    }
}
