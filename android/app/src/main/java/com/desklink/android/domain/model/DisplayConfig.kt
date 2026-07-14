package com.desklink.android.domain.model

data class DisplayConfig(
    val width: Int = 1920,
    val height: Int = 1200,
    val fps: Int = 60,
    val codec: Codec = Codec.HEVC,
    val bitrateKbps: Int = 20_000,
    val keyframeInterval: Int = 2,
    val nativeWidth: Int = 1920,
    val nativeHeight: Int = 1200,
) {
    fun oriented(rotation: DisplayRotation): DisplayConfig {
        val longEdge = maxOf(width, height)
        val shortEdge = minOf(width, height)
        return if (rotation.isPortrait) {
            copy(width = shortEdge, height = longEdge)
        } else {
            copy(width = longEdge, height = shortEdge)
        }
    }

    enum class Codec(val id: Byte) {
        HEVC(0x01),
        H264(0x02);

        companion object {
            fun fromId(id: Byte): Codec =
                entries.firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException("Unknown codec id: $id")
        }
    }

    companion object {
        fun recommendedBitrateKbps(width: Int): Int = when {
            width >= 2560 -> 40_000
            width >= 1920 -> 25_000
            width >= 1280 -> 15_000
            else -> 10_000
        }

        fun forNativeResolution(screenWidth: Int, screenHeight: Int): DisplayConfig {
            if (screenWidth <= 0 || screenHeight <= 0) return DisplayConfig()
            val w = maxOf(screenWidth, screenHeight)
            val h = minOf(screenWidth, screenHeight)
            return DisplayConfig(
                width = w,
                height = h,
                fps = 60,
                codec = Codec.HEVC,
                bitrateKbps = recommendedBitrateKbps(w),
                nativeWidth = w,
                nativeHeight = h,
            )
        }
    }
}
