import Foundation
import os

/// Hands captured PCM from Core Audio's real-time IO proc to a normal Swift task.
///
/// Why a ring buffer instead of yielding straight into an `AsyncStream`: the IO proc
/// runs on a real-time audio thread with a hard deadline. Allocating, taking a
/// contended lock, or entering the Swift concurrency runtime there makes the tap miss
/// its deadline, which is audible as a click or dropout.
///
/// Every allocation therefore happens once, in `init`:
/// - slot payloads are one flat `UnsafeMutableRawPointer`,
/// - slot metadata is an `UnsafeMutablePointer<Slot>` rather than an `Array`, because a
///   Swift `Array` puts exclusivity checks and a copy-on-write malloc path inside the
///   real-time function even when uniqueness happens to hold,
/// - the lock is a separately allocated `os_unfair_lock`, because taking `&` of a class
///   stored property does not guarantee a stable address.
///
/// The producer writes THROUGH the buffer via `withWritableSlot`: it borrows the slot's
/// memory and converts into it directly, so no temporary buffer is needed on the
/// real-time side at all. The consumer copies out under the lock into a pre-allocated
/// bounce buffer and builds the `Data` outside it, so a consumer allocation can never
/// block the IO proc.
final class AudioChunkRingBuffer: @unchecked Sendable {

    enum WriteResult: Equatable {
        /// Stored into a free slot.
        case stored
        /// Stored, but the buffer was full so the oldest pending chunk was discarded.
        case droppedOldest
        /// Nothing to store (zero-length render quantum).
        case ignoredEmpty
    }

    /// One pending chunk: how many bytes of its slot are valid, and when it was captured.
    private struct Slot {
        var byteCount = 0
        var timestampUs: Int64 = 0
    }

    private let slotCount: Int
    let slotCapacityBytes: Int

    /// Flat backing store for every slot, allocated once. Slot `i` occupies
    /// `[i * slotCapacityBytes, (i + 1) * slotCapacityBytes)`.
    private let storage: UnsafeMutableRawPointer
    private let slots: UnsafeMutablePointer<Slot>
    /// Pre-allocated staging buffer so `read` copies under the lock but allocates outside it.
    private let bounce: UnsafeMutableRawPointer
    private let lock: UnsafeMutablePointer<os_unfair_lock>

    private var readIndex = 0
    private var pendingCount = 0
    private var dropped = 0

    /// Chunks lost to overflow or refused for being oversized, for the current session.
    /// A non-zero value means the consumer is not keeping up.
    var droppedChunkCount: Int {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        return dropped
    }

    init(slotCount: Int, slotCapacityBytes: Int) {
        precondition(slotCount > 0 && slotCapacityBytes > 0, "ring buffer needs at least one usable slot")
        self.slotCount = slotCount
        self.slotCapacityBytes = slotCapacityBytes
        self.storage = UnsafeMutableRawPointer.allocate(
            byteCount: slotCount * slotCapacityBytes,
            alignment: MemoryLayout<Int16>.alignment
        )
        self.slots = UnsafeMutablePointer<Slot>.allocate(capacity: slotCount)
        self.slots.initialize(repeating: Slot(), count: slotCount)
        self.bounce = UnsafeMutableRawPointer.allocate(
            byteCount: slotCapacityBytes,
            alignment: MemoryLayout<Int16>.alignment
        )
        self.lock = UnsafeMutablePointer<os_unfair_lock>.allocate(capacity: 1)
        self.lock.initialize(to: os_unfair_lock_s())
    }

    deinit {
        storage.deallocate()
        slots.deinitialize(count: slotCount)
        slots.deallocate()
        bounce.deallocate()
        lock.deinitialize(count: 1)
        lock.deallocate()
    }

    /// Discards everything pending and zeroes the drop counter.
    ///
    /// Required between capture sessions: leftover slots from a previous session carry
    /// that session's timestamps, and replaying them into a new connection would emit a
    /// burst of stale audio — which is precisely the lip-sync failure this whole
    /// component exists to avoid.
    func reset() {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        readIndex = 0
        pendingCount = 0
        dropped = 0
    }

    /// Borrows the next writable slot so the producer can render straight into it.
    ///
    /// `fill` receives the slot's memory and its capacity, and returns how many bytes it
    /// actually wrote; returning 0 abandons the slot. Real-time safe: no allocation, and
    /// the lock covers only bookkeeping and the caller's own write.
    func withWritableSlot(
        timestampUs: Int64,
        fill: (UnsafeMutableRawPointer, Int) -> Int
    ) -> WriteResult {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }

        var result = WriteResult.stored
        if pendingCount == slotCount {
            // Full: discard the OLDEST chunk. Keeping stale audio instead would let the
            // audio lag the video by however long the consumer was blocked, and that
            // skew would never be recovered.
            readIndex = (readIndex + 1) % slotCount
            pendingCount -= 1
            dropped += 1
            result = .droppedOldest
        }

        let writeIndex = (readIndex + pendingCount) % slotCount
        let destination = storage.advanced(by: writeIndex * slotCapacityBytes)
        let written = fill(destination, slotCapacityBytes)

        guard written > 0 else {
            precondition(written == 0, "producer reported a negative byte count")
            // Nothing was produced, so no slot is claimed. An eviction that already
            // happened cannot be undone, and the caller still needs to know about it.
            return result == .droppedOldest ? .droppedOldest : .ignoredEmpty
        }
        precondition(written <= slotCapacityBytes, "producer wrote past the slot it was lent")

        slots[writeIndex] = Slot(byteCount: written, timestampUs: timestampUs)
        pendingCount += 1
        return result
    }

    /// Records that a chunk was too large to store, without pretending to write it.
    ///
    /// Kept explicit rather than having the producer call `withWritableSlot` with a
    /// bogus size just to bump the counter: passing an argument that does not describe
    /// the real buffer is exactly the kind of shortcut that becomes an overread the
    /// moment the guard order changes.
    func recordOversizedChunk() {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        dropped += 1
    }

    /// Takes the oldest pending chunk, or nil when empty.
    ///
    /// The payload is copied into the pre-allocated bounce buffer while the lock is
    /// held, and the `Data` is allocated after releasing it — so the real-time producer
    /// is never blocked waiting on the consumer's allocator.
    func read() -> (pcm: Data, timestampUs: Int64)? {
        os_unfair_lock_lock(lock)
        guard pendingCount > 0 else {
            os_unfair_lock_unlock(lock)
            return nil
        }
        let slot = slots[readIndex]
        // Only `byteCount` bytes are valid; the rest of the slot still holds the
        // previous, longer chunk's tail.
        bounce.copyMemory(from: storage.advanced(by: readIndex * slotCapacityBytes), byteCount: slot.byteCount)
        readIndex = (readIndex + 1) % slotCount
        pendingCount -= 1
        os_unfair_lock_unlock(lock)

        return (Data(bytes: bounce, count: slot.byteCount), slot.timestampUs)
    }
}
