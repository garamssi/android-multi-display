import XCTest
import os
@testable import DeskLink

/// The Core Audio tap's IO proc runs on a real-time thread: allocating, locking for
/// long, or awaiting there causes audible dropouts. So the IO proc renders straight
/// into a pre-allocated slot and the consumer task turns slots into `AudioChunk`s. This
/// ring buffer is that handoff, and it is pure logic — so it is tested directly.
final class AudioChunkRingBufferTests: XCTestCase {

    /// Writes `bytes` through the borrow-a-slot API the producer uses.
    private func write(_ buffer: AudioChunkRingBuffer, _ bytes: [UInt8], timestampUs: Int64)
        -> AudioChunkRingBuffer.WriteResult {
        buffer.withWritableSlot(timestampUs: timestampUs) { destination, capacity in
            guard bytes.count <= capacity else { return 0 }
            bytes.withUnsafeBytes { destination.copyMemory(from: $0.baseAddress!, byteCount: $0.count) }
            return bytes.count
        }
    }

    func testWriteThenReadRoundTrip() {
        let buffer = AudioChunkRingBuffer(slotCount: 4, slotCapacityBytes: 16)
        XCTAssertEqual(write(buffer, [1, 2, 3, 4], timestampUs: 1_000), .stored)

        let slot = buffer.read()
        XCTAssertEqual(slot?.pcm, Data([1, 2, 3, 4]))
        XCTAssertEqual(slot?.timestampUs, 1_000)
    }

    func testReadOnEmptyBufferReturnsNil() {
        XCTAssertNil(AudioChunkRingBuffer(slotCount: 4, slotCapacityBytes: 16).read())
    }

    func testDrainsInFifoOrder() {
        let buffer = AudioChunkRingBuffer(slotCount: 4, slotCapacityBytes: 16)
        XCTAssertEqual(write(buffer, [0x0A], timestampUs: 10), .stored)
        XCTAssertEqual(write(buffer, [0x0B], timestampUs: 20), .stored)
        XCTAssertEqual(write(buffer, [0x0C], timestampUs: 30), .stored)

        XCTAssertEqual(buffer.read()?.timestampUs, 10)
        XCTAssertEqual(buffer.read()?.timestampUs, 20)
        XCTAssertEqual(buffer.read()?.timestampUs, 30)
        XCTAssertNil(buffer.read())
    }

    func testWrapsAroundAcrossManyCycles() {
        let buffer = AudioChunkRingBuffer(slotCount: 2, slotCapacityBytes: 8)
        for round in 0..<10 {
            let stamp = Int64(round * 100)
            XCTAssertEqual(write(buffer, [UInt8(round)], timestampUs: stamp), .stored)
            XCTAssertEqual(buffer.read()?.timestampUs, stamp)
        }
        XCTAssertEqual(buffer.droppedChunkCount, 0)
    }

    /// On overflow the OLDEST chunk is dropped, not the newest. If the consumer falls
    /// behind, keeping stale audio would grow the audio/video skew without bound.
    func testOverflowDropsOldestChunk() {
        let buffer = AudioChunkRingBuffer(slotCount: 2, slotCapacityBytes: 8)
        XCTAssertEqual(write(buffer, [0x01], timestampUs: 100), .stored)
        XCTAssertEqual(write(buffer, [0x02], timestampUs: 200), .stored)
        XCTAssertEqual(write(buffer, [0x03], timestampUs: 300), .droppedOldest)

        XCTAssertEqual(buffer.read()?.timestampUs, 200)
        XCTAssertEqual(buffer.read()?.timestampUs, 300)
        XCTAssertNil(buffer.read())
        XCTAssertEqual(buffer.droppedChunkCount, 1)
    }

    func testAcceptsChunkExactlyAtSlotCapacity() {
        let buffer = AudioChunkRingBuffer(slotCount: 2, slotCapacityBytes: 4)
        XCTAssertEqual(write(buffer, [1, 2, 3, 4], timestampUs: 1), .stored)
        XCTAssertEqual(buffer.read()?.pcm, Data([1, 2, 3, 4]))
    }

    /// A producer that writes nothing must not claim a slot or emit an empty chunk.
    func testAbandonedSlotIsNotStored() {
        let buffer = AudioChunkRingBuffer(slotCount: 2, slotCapacityBytes: 4)
        XCTAssertEqual(buffer.withWritableSlot(timestampUs: 1) { _, _ in 0 }, .ignoredEmpty)
        XCTAssertNil(buffer.read())
        XCTAssertEqual(buffer.droppedChunkCount, 0)
    }

    /// Slots are reused, so a short chunk written into a slot that previously held a
    /// long one must not expose the stale tail.
    func testShortChunkDoesNotExposeStaleBytesFromReusedSlot() {
        let buffer = AudioChunkRingBuffer(slotCount: 1, slotCapacityBytes: 8)
        XCTAssertEqual(write(buffer, [1, 2, 3, 4, 5, 6, 7, 8], timestampUs: 1), .stored)
        XCTAssertEqual(buffer.read()?.pcm.count, 8)
        XCTAssertEqual(write(buffer, [9, 9], timestampUs: 2), .stored)
        XCTAssertEqual(buffer.read()?.pcm, Data([9, 9]))
    }

    /// An oversized chunk is counted WITHOUT being handed a slot, so the producer never
    /// has to lie about a buffer size to register the drop.
    func testOversizedChunkIsCountedWithoutStoring() {
        let buffer = AudioChunkRingBuffer(slotCount: 2, slotCapacityBytes: 4)
        buffer.recordOversizedChunk()
        XCTAssertNil(buffer.read())
        XCTAssertEqual(buffer.droppedChunkCount, 1)
    }

    /// Between capture sessions the buffer must be empty. Leftover slots carry the
    /// PREVIOUS session's timestamps, and replaying them into a new connection emits a
    /// burst of stale audio — the exact lip-sync failure this component guards against.
    func testResetDiscardsPendingChunksAndDropCount() {
        let buffer = AudioChunkRingBuffer(slotCount: 2, slotCapacityBytes: 8)
        XCTAssertEqual(write(buffer, [0x01], timestampUs: 100), .stored)
        XCTAssertEqual(write(buffer, [0x02], timestampUs: 200), .stored)
        XCTAssertEqual(write(buffer, [0x03], timestampUs: 300), .droppedOldest)
        XCTAssertEqual(buffer.droppedChunkCount, 1)

        buffer.reset()

        XCTAssertNil(buffer.read(), "stale chunk survived reset")
        XCTAssertEqual(buffer.droppedChunkCount, 0, "drop count must be per-session")
    }

    func testWritesAfterResetStartClean() {
        let buffer = AudioChunkRingBuffer(slotCount: 2, slotCapacityBytes: 8)
        _ = write(buffer, [0x01], timestampUs: 100)
        buffer.reset()
        XCTAssertEqual(write(buffer, [0x42], timestampUs: 999), .stored)
        let slot = buffer.read()
        XCTAssertEqual(slot?.timestampUs, 999)
        XCTAssertEqual(slot?.pcm, Data([0x42]))
    }

    /// Concurrent producer and consumer must not lose or corrupt chunks. This is the
    /// arrangement in production (real-time IO proc writing, task reading) and is where
    /// a lock-coverage mistake would show up.
    func testConcurrentProducerAndConsumerPreserveEveryChunk() {
        let totalChunks = 5_000
        // Sized so the consumer cannot fall irrecoverably behind and force drops.
        let buffer = AudioChunkRingBuffer(slotCount: 256, slotCapacityBytes: 8)
        let consumed = OSAllocatedUnfairLock(initialState: [Int64]())
        let producerDone = OSAllocatedUnfairLock(initialState: false)

        let consumer = Thread {
            while true {
                if let slot = buffer.read() {
                    XCTAssertEqual(slot.pcm.count, 8)
                    consumed.withLock { $0.append(slot.timestampUs) }
                } else if producerDone.withLock({ $0 }) {
                    // Drain whatever landed between the last read and the flag.
                    while let slot = buffer.read() {
                        consumed.withLock { $0.append(slot.timestampUs) }
                    }
                    return
                }
            }
        }
        consumer.start()

        var payload = [UInt8](repeating: 0, count: 8)
        for index in 0..<totalChunks {
            var result = write(buffer, payload, timestampUs: Int64(index))
            // Retry rather than accept a drop, so the assertion below is about
            // correctness of the handoff and not about timing.
            while result == .droppedOldest {
                result = write(buffer, payload, timestampUs: Int64(index))
            }
            payload[0] = UInt8(index % 256)
        }
        producerDone.withLock { $0 = true }

        let deadline = Date().addingTimeInterval(10)
        while !consumer.isFinished && Date() < deadline {
            usleep(1_000)
        }

        let received = consumed.withLock { $0 }
        XCTAssertEqual(received.count, totalChunks, "chunks lost or duplicated across threads")
        XCTAssertEqual(received, received.sorted(), "chunks arrived out of order")
    }
}
