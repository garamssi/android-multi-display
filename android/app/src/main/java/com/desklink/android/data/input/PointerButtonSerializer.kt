package com.desklink.android.data.input

import com.desklink.android.domain.model.PointerButtonEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes POINTER_BUTTON to wire format per protocol spec:
 * Button(1) + Action(1) + X(f32) + Y(f32) = 10 bytes, Big-Endian.
 */
object PointerButtonSerializer {

    fun serialize(event: PointerButtonEvent): ByteArray {
        val buffer = ByteBuffer.allocate(PointerButtonEvent.SERIALIZED_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(event.button.value)
        buffer.put(event.action.value)
        buffer.putFloat(event.x)
        buffer.putFloat(event.y)
        return buffer.array()
    }

    fun deserialize(data: ByteArray): PointerButtonEvent {
        require(data.size >= PointerButtonEvent.SERIALIZED_SIZE) {
            "Data too small: ${data.size} bytes, need ${PointerButtonEvent.SERIALIZED_SIZE}"
        }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val button = buffer.get().toButton()
        val action = buffer.get().toAction()
        return PointerButtonEvent(button = button, action = action, x = buffer.float, y = buffer.float)
    }

    private fun Byte.toButton(): PointerButtonEvent.Button =
        PointerButtonEvent.Button.entries.first { it.value == this }

    private fun Byte.toAction(): PointerButtonEvent.Action =
        PointerButtonEvent.Action.entries.first { it.value == this }
}
