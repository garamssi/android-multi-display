package com.desklink.android.data.codec

import android.view.Choreographer

/**
 * Drives the decode-render loop synchronized to display vsync
 * using Choreographer.FrameCallback.
 */
class VsyncRenderer(
    private val decoder: HEVCDecoder,
) : Choreographer.FrameCallback {

    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return

        // Render as many decoded frames as available
        decoder.renderFrame()

        // Schedule next vsync callback
        Choreographer.getInstance().postFrameCallback(this)
    }
}
