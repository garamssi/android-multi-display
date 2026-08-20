package com.desklink.android.data.input

import com.desklink.android.domain.model.TouchEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TouchSerializer {

    fun serialize(event: TouchEvent): ByteArray {
        val buffer = ByteBuffer.allocate(TouchEvent.SERIALIZED_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(event.action.code)
        buffer.putFloat(event.x)
        buffer.putFloat(event.y)
        buffer.putShort(event.pressure.toShort())
        buffer.put(event.pointerId.toByte())
        buffer.putLong(event.timestampUs)
        return buffer.array()
    }

    fun serializeBatch(events: List<TouchEvent>): ByteArray {
        val count = events.size.coerceAtMost(100)
        val buffer = ByteBuffer.allocate(2 + count * TouchEvent.SERIALIZED_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(count.toShort())
        for (i in 0 until count) {
            buffer.put(serialize(events[i]))
        }
        return buffer.array()
    }

    fun deserialize(data: ByteArray): TouchEvent {
        require(data.size >= TouchEvent.SERIALIZED_SIZE) {
            "Data too small: ${data.size} bytes, need ${TouchEvent.SERIALIZED_SIZE}"
        }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        return TouchEvent(
            action = TouchEvent.Action.fromCode(buffer.get()),
            x = buffer.float,
            y = buffer.float,
            pressure = buffer.short.toUShort(),
            pointerId = buffer.get().toUByte(),
            timestampUs = buffer.long,
        )
    }
}
