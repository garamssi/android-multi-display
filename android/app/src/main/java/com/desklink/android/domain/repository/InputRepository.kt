package com.desklink.android.domain.repository

import com.desklink.android.domain.model.PointerButtonEvent
import com.desklink.android.domain.model.TouchEvent

interface InputRepository {
    suspend fun sendTouchEvent(event: TouchEvent)
    suspend fun sendTouchBatch(events: List<TouchEvent>)
    /** Sends a scroll gesture as normalized deltas (fraction of the view). */
    suspend fun sendScroll(deltaX: Float, deltaY: Float)
    /** Sends a pointer button press/release (e.g. long-press mapped to right-click). */
    suspend fun sendPointerButton(event: PointerButtonEvent)
    suspend fun connect()
    suspend fun disconnect()
}
