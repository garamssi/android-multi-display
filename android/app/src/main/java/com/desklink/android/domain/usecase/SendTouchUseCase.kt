package com.desklink.android.domain.usecase

import com.desklink.android.domain.model.TouchEvent
import com.desklink.android.domain.repository.InputRepository
import javax.inject.Inject

class SendTouchUseCase @Inject constructor(
    private val inputRepository: InputRepository,
) {
    suspend fun send(event: TouchEvent) {
        inputRepository.sendTouchEvent(event)
    }

    suspend fun sendBatch(events: List<TouchEvent>) {
        inputRepository.sendTouchBatch(events)
    }
}
