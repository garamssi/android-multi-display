package com.desklink.android.domain.model

/**
 * A scroll gesture delta, normalized as a fraction of the view (device-independent,
 * like [TouchEvent] coordinates). [deltaX] > 0 = fingers moved right; [deltaY] > 0 =
 * fingers moved down. The Mac converts these to a pixel scroll against the display.
 */
data class ScrollEvent(val deltaX: Float, val deltaY: Float) {
    companion object {
        /** Wire payload size: DeltaX(f32) + DeltaY(f32) = 8 bytes, Big-Endian. */
        const val SERIALIZED_SIZE = 8
    }
}
