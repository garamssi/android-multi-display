package com.desklink.android.data.codec

import android.view.Choreographer

class VsyncRenderer(
    private val renderTick: (frameTimeNanos: Long, vsyncPeriodNanos: Long) -> Boolean,
) : Choreographer.FrameCallback {

    constructor(decoder: HEVCDecoder) : this({ frameTimeNanos, vsyncPeriodNanos ->
        decoder.renderFrame(frameTimeNanos, vsyncPeriodNanos)
    })

    @Volatile
    private var isRunning = false

    private var lastFrameTimeNanos = 0L

    // Smoothed interval between callbacks, which IS the display's vsync period. Measuring
    // it costs nothing here and keeps the lip-sync reference correct on 90/120/144 Hz
    // panels, where assuming 60 Hz misstates when a rendered frame is actually seen.
    private var vsyncPeriodNanos = DEFAULT_VSYNC_PERIOD_NANOS

    fun start() {
        if (isRunning) return
        isRunning = true
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return

        updateVsyncPeriod(frameTimeNanos)
        renderTick(frameTimeNanos, vsyncPeriodNanos)

        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun updateVsyncPeriod(frameTimeNanos: Long) {
        val previous = lastFrameTimeNanos
        lastFrameTimeNanos = frameTimeNanos
        if (previous == 0L) return

        val interval = frameTimeNanos - previous
        // A dropped callback reports a multiple of the period, so only intervals within a
        // plausible refresh range are used; the rest would inflate the estimate.
        if (interval !in MIN_VSYNC_PERIOD_NANOS..MAX_VSYNC_PERIOD_NANOS) return
        vsyncPeriodNanos += (interval - vsyncPeriodNanos) / PERIOD_SMOOTHING_DIVISOR
    }

    private companion object {
        // 60 Hz, used until two callbacks have been observed.
        const val DEFAULT_VSYNC_PERIOD_NANOS = 16_666_667L

        // 240 Hz and 24 Hz: outside this range the interval is a dropped frame or a
        // paused compositor, not the panel's period.
        const val MIN_VSYNC_PERIOD_NANOS = 4_000_000L
        const val MAX_VSYNC_PERIOD_NANOS = 41_666_667L

        // Divisor of the error folded in per callback, so a single late callback inside
        // the plausible range barely moves the estimate.
        const val PERIOD_SMOOTHING_DIVISOR = 8
    }
}
