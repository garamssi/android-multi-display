package com.desklink.android.domain.model

/**
 * How this tablet fits the picture to its own panel, as chosen on the Mac.
 *
 * Mirror sends the Mac's own screen, whose shape has nothing to do with this panel's, and
 * there is no way to show one on the other without paying something: keep every pixel and
 * accept bars, or use the whole panel and lose the edges.
 */
enum class VideoScaling(val wire: String) {
    /** The whole picture, with bars where the shapes differ. */
    FIT("fit"),

    /** The whole panel, cropping whatever does not fit. */
    FILL("fill");

    companion object {
        /** Fit, because it loses nothing -- also what a server that names nothing gets. */
        val DEFAULT = FIT

        fun fromWire(value: String?): VideoScaling =
            entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}
