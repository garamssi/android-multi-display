package com.desklink.android.data.input

import android.view.MotionEvent
import com.desklink.android.domain.model.TouchEvent

/**
 * Collects multi-touch events from Android MotionEvent and converts them
 * to normalized protocol TouchEvent format.
 *
 * Coordinates are normalized to 0.0-1.0 range relative to the view dimensions.
 */
object TouchCollector {

    /**
     * Converts an Android MotionEvent into a list of protocol TouchEvents.
     * Handles multi-touch by iterating over all active pointers.
     *
     * @param event The Android MotionEvent
     * @param viewWidth The width of the touch surface view in pixels
     * @param viewHeight The height of the touch surface view in pixels
     * @return List of TouchEvents with normalized coordinates
     */
    fun collect(event: MotionEvent, viewWidth: Int, viewHeight: Int): List<TouchEvent> {
        if (viewWidth <= 0 || viewHeight <= 0) return emptyList()

        val timestampUs = event.eventTime * 1000L // ms to us
        val events = mutableListOf<TouchEvent>()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                events.add(
                    createTouchEvent(
                        event, pointerIndex, TouchEvent.Action.DOWN,
                        viewWidth, viewHeight, timestampUs
                    )
                )
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                events.add(
                    createTouchEvent(
                        event, pointerIndex, TouchEvent.Action.UP,
                        viewWidth, viewHeight, timestampUs
                    )
                )
            }

            MotionEvent.ACTION_MOVE -> {
                // Report all active pointers on move
                for (i in 0 until event.pointerCount) {
                    events.add(
                        createTouchEvent(
                            event, i, TouchEvent.Action.MOVE,
                            viewWidth, viewHeight, timestampUs
                        )
                    )
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) {
                    events.add(
                        createTouchEvent(
                            event, i, TouchEvent.Action.CANCEL,
                            viewWidth, viewHeight, timestampUs
                        )
                    )
                }
            }
        }

        return events
    }

    private fun createTouchEvent(
        event: MotionEvent,
        pointerIndex: Int,
        action: TouchEvent.Action,
        viewWidth: Int,
        viewHeight: Int,
        timestampUs: Long,
    ): TouchEvent {
        val x = (event.getX(pointerIndex) / viewWidth).coerceIn(0f, 1f)
        val y = (event.getY(pointerIndex) / viewHeight).coerceIn(0f, 1f)
        val pressure = (event.getPressure(pointerIndex) * 65535).toInt()
            .coerceIn(0, 65535).toUShort()
        val pointerId = event.getPointerId(pointerIndex).coerceIn(0, 9).toUByte()

        return TouchEvent(
            action = action,
            x = x,
            y = y,
            pressure = pressure,
            pointerId = pointerId,
            timestampUs = timestampUs,
        )
    }
}
