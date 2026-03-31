package com.desklink.android.domain.repository

import com.desklink.android.domain.model.TouchEvent

interface InputRepository {
    suspend fun sendTouchEvent(event: TouchEvent)
    suspend fun sendTouchBatch(events: List<TouchEvent>)
    suspend fun connect()
    suspend fun disconnect()
}
