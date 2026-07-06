package com.desklink.android.domain.usecase

import com.desklink.android.domain.model.PointerButtonEvent
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

    suspend fun sendScroll(deltaX: Float, deltaY: Float) {
        inputRepository.sendScroll(deltaX, deltaY)
    }

    /**
     * Injects a right-click at a normalized position as a DOWN then UP pair, mirroring
     * how a physical secondary click presses and releases. [x]/[y] are clamped to [0,1].
     */
    suspend fun sendRightClick(x: Float, y: Float) {
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        inputRepository.sendPointerButton(
            PointerButtonEvent(PointerButtonEvent.Button.RIGHT, PointerButtonEvent.Action.DOWN, cx, cy),
        )
        inputRepository.sendPointerButton(
            PointerButtonEvent(PointerButtonEvent.Button.RIGHT, PointerButtonEvent.Action.UP, cx, cy),
        )
    }
}
