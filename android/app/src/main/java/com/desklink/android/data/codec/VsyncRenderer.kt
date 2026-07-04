package com.desklink.android.data.codec

import android.view.Choreographer

/**
 * Drives the decode-render loop synchronized to display vsync using
 * [Choreographer.FrameCallback].
 *
 * Takes a [renderTick] lambda that drains + renders all decoded frames ready this
 * vsync (returns true if any were rendered). This decouples the renderer from the
 * concrete decoder so it can be driven via the video-stream repository.
 */
class VsyncRenderer(
    private val renderTick: () -> Boolean,
) : Choreographer.FrameCallback {

    /** Convenience constructor driving an [HEVCDecoder] directly. */
    constructor(decoder: HEVCDecoder) : this({ decoder.renderFrame() })

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

        // renderTick() internally drains ALL decoded output buffers ready this
        // vsync, so a single call per frame keeps latency low without backlog.
        renderTick()

        // Schedule next vsync callback.
        Choreographer.getInstance().postFrameCallback(this)
    }
}
