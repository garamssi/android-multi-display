package com.desklink.android.domain

import com.desklink.android.domain.model.PointerButtonEvent
import com.desklink.android.domain.model.TouchEvent
import com.desklink.android.domain.repository.InputRepository
import com.desklink.android.domain.usecase.SendTouchUseCase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SendTouchUseCaseTest {

    private class RecordingInputRepository : InputRepository {
        val buttons = mutableListOf<PointerButtonEvent>()
        override suspend fun sendTouchEvent(event: TouchEvent) {}
        override suspend fun sendTouchBatch(events: List<TouchEvent>) {}
        override suspend fun sendScroll(deltaX: Float, deltaY: Float) {}
        override suspend fun sendPointerButton(event: PointerButtonEvent) {
            buttons.add(event)
        }
        override suspend fun connect() {}
        override suspend fun disconnect() {}
    }

    @Test
    fun `sendRightClick emits a right-button down then up at the position`() = runTest {
        val repo = RecordingInputRepository()
        val useCase = SendTouchUseCase(repo)

        useCase.sendRightClick(0.4f, 0.6f)

        assertEquals(2, repo.buttons.size)
        assertEquals(
            PointerButtonEvent(PointerButtonEvent.Button.RIGHT, PointerButtonEvent.Action.DOWN, 0.4f, 0.6f),
            repo.buttons[0],
        )
        assertEquals(
            PointerButtonEvent(PointerButtonEvent.Button.RIGHT, PointerButtonEvent.Action.UP, 0.4f, 0.6f),
            repo.buttons[1],
        )
    }

    @Test
    fun `sendRightClick clamps out-of-range coordinates`() = runTest {
        val repo = RecordingInputRepository()
        val useCase = SendTouchUseCase(repo)

        useCase.sendRightClick(-0.2f, 1.5f)

        assertEquals(2, repo.buttons.size)
        assertEquals(0f, repo.buttons[0].x)
        assertEquals(1f, repo.buttons[0].y)
    }
}
