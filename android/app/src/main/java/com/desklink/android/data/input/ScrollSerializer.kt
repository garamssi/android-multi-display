package com.desklink.android.data.input

import com.desklink.android.domain.model.ScrollEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes SCROLL to wire format per protocol spec:
 * DeltaX(4 float32) + DeltaY(4 float32) = 8 bytes, Big-Endian.
 */
object ScrollSerializer {

    fun serialize(scroll: ScrollEvent): ByteArray {
        val buffer = ByteBuffer.allocate(ScrollEvent.SERIALIZED_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.putFloat(scroll.deltaX)
        buffer.putFloat(scroll.deltaY)
        return buffer.array()
    }

    fun deserialize(data: ByteArray): ScrollEvent {
        require(data.size >= ScrollEvent.SERIALIZED_SIZE) {
            "Data too small: ${data.size} bytes, need ${ScrollEvent.SERIALIZED_SIZE}"
        }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        return ScrollEvent(deltaX = buffer.float, deltaY = buffer.float)
    }
}
