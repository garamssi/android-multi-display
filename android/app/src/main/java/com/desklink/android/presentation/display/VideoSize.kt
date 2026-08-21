package com.desklink.android.presentation.display

/**
 * The decoded picture's dimensions in pixels.
 *
 * Not the negotiated resolution: that is what the Mac was asked for, and in mirror mode it
 * captures its own screen's size instead. The view needs the real one to keep the picture's
 * shape instead of stretching it to the panel's.
 */
data class VideoSize(val width: Int, val height: Int)
