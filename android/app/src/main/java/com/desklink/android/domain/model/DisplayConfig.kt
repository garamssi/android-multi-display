package com.desklink.android.domain.model

data class DisplayConfig(
    val width: Int = 1920,
    val height: Int = 1200,
    val fps: Int = 60,
    val codec: Codec = Codec.HEVC,
    val bitrateKbps: Int = 20_000,
    val keyframeInterval: Int = 2,
    /**
     * The device's real native screen size, normalised to landscape
     * ([nativeWidth] >= [nativeHeight]). Advertised to the Mac in the handshake so its
     * width clamp (`min(requestedWidth, advertisedScreenWidth)`) never caps the
     * requested streaming resolution below what the panel can actually display.
     *
     * This is intentionally independent of [width]/[height], which are the *requested
     * streaming* resolution the user picked. The native size is preserved across every
     * user edit so the handshake always advertises the true panel size, even when the
     * user chooses a smaller streaming resolution.
     */
    val nativeWidth: Int = 1920,
    val nativeHeight: Int = 1200,
) {
    /**
     * Returns this config with [width]/[height] oriented for [rotation]: portrait
     * rotations send the long edge as [height] (tall), landscape rotations keep the long
     * edge as [width]. [nativeWidth]/[nativeHeight] are left untouched — they stay
     * landscape-normalised so the handshake always advertises the true panel size, which
     * the Mac clamps orientation-agnostically. Pure so the mapping is unit-testable.
     */
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
        /**
         * Recommended encoder bitrate (kbps) for a given streaming [width]. Higher
         * resolutions need more bits to stay sharp. The Mac clamps to
         * `max(1000, min(requested, 40000))`, so 40 Mbps is the useful ceiling.
         */
        fun recommendedBitrateKbps(width: Int): Int = when {
            width >= 2560 -> 40_000
            width >= 1920 -> 25_000
            width >= 1280 -> 15_000
            else -> 10_000
        }

        /**
         * Builds the effective default config for a device whose real screen size is
         * [screenWidth] x [screenHeight] (reported in any orientation). The result is
         * normalised to landscape (width >= height, since the tablet is used as a
         * landscape extended display), defaults the requested streaming resolution to
         * the native size, and picks a matching bitrate. HEVC / 60fps are kept.
         *
         * Falls back to the plain [DisplayConfig] default (1920x1200) if detection
         * yielded non-positive dimensions.
         */
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
