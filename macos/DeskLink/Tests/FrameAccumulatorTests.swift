import XCTest
@testable import DeskLink

final class FrameAccumulatorTests: XCTestCase {

    func testSingleCompleteFrame() throws {
        let payload = Data([0xAA, 0xBB])
        let packet = try PacketFramer.frame(type: .pong, payload: payload)

        var acc = FrameAccumulator()
        let frames = try acc.append(packet)
        XCTAssertEqual(frames, [FrameAccumulator.Frame(type: .pong, payload: payload)])
        XCTAssertEqual(acc.bufferedByteCount, 0)
    }

    func testMultipleFramesInOneChunk() throws {
        let p1 = try PacketFramer.frame(type: .ping, payload: Data([0x01]))
        let p2 = try PacketFramer.frame(type: .pong, payload: Data([0x02]))

        var acc = FrameAccumulator()
        let frames = try acc.append(p1 + p2)
        XCTAssertEqual(frames.count, 2)
        XCTAssertEqual(frames[0].type, .ping)
        XCTAssertEqual(frames[1].type, .pong)
    }

    /// A frame split across two chunks must be reassembled (TCP byte-stream case).
    func testFrameSplitAcrossChunks() throws {
        let payload = Data([0x11, 0x22, 0x33, 0x44])
        let packet = try PacketFramer.frame(type: .touchEvent, payload: payload)

        let mid = packet.count / 2
        let first = packet.subdata(in: 0..<mid)
        let second = packet.subdata(in: mid..<packet.count)

        var acc = FrameAccumulator()
        var frames = try acc.append(first)
        XCTAssertTrue(frames.isEmpty)           // incomplete
        XCTAssertEqual(acc.bufferedByteCount, first.count)

        frames = try acc.append(second)
        XCTAssertEqual(frames, [FrameAccumulator.Frame(type: .touchEvent, payload: payload)])
        XCTAssertEqual(acc.bufferedByteCount, 0)
    }

    /// One-byte-at-a-time delivery still reassembles correctly.
    func testByteByByteDelivery() throws {
        let payload = Data([0xDE, 0xAD, 0xBE, 0xEF])
        let packet = try PacketFramer.frame(type: .videoConfig, payload: payload)

        var acc = FrameAccumulator()
        var collected: [FrameAccumulator.Frame] = []
        for byte in packet {
            collected += try acc.append(Data([byte]))
        }
        XCTAssertEqual(collected, [FrameAccumulator.Frame(type: .videoConfig, payload: payload)])
    }

    func testUnknownTypeThrows() {
        var buffer = Data()
        var length = UInt32(1).bigEndian
        buffer.append(Data(bytes: &length, count: 4))
        buffer.append(0xFF) // unknown type

        var acc = FrameAccumulator()
        XCTAssertThrowsError(try acc.append(buffer)) { error in
            guard case FrameAccumulator.AccumulateError.protocolError = error else {
                return XCTFail("Expected protocolError, got \(error)")
            }
        }
    }
}
