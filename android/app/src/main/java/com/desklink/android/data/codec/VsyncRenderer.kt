package com.desklink.android.data.codec

import android.view.Choreographer

class VsyncRenderer(
    private val renderTick: () -> Boolean,
) : Choreographer.FrameCallback {

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

        renderTick()

        Choreographer.getInstance().postFrameCallback(this)
    }
}
