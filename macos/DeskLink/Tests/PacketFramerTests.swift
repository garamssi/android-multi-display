import XCTest
@testable import DeskLink

final class PacketFramerTests: XCTestCase {

    func testFrameCreatesValidPacket() throws {
        let payload = Data([0x01, 0x02, 0x03])
        let packet = try PacketFramer.frame(type: .ping, payload: payload)

        // Length = 1 (type) + 3 (payload) = 4
        // Total = 4 (length header) + 1 (type) + 3 (payload) = 8
        XCTAssertEqual(packet.count, 8)

        // First 4 bytes: length in big-endian
        let length = packet.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt32.self).bigEndian }
        XCTAssertEqual(length, 4) // type + payload

        // 5th byte: type
        XCTAssertEqual(packet[4], MessageType.ping.rawValue)

        // Remaining: payload
        XCTAssertEqual(packet.subdata(in: 5..<8), payload)
    }

    func testFrameEmptyPayload() throws {
        let packet = try PacketFramer.frame(type: .disconnect, payload: Data())
        XCTAssertEqual(packet.count, 5) // 4 header + 1 type + 0 payload

        let length = packet.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt32.self).bigEndian }
        XCTAssertEqual(length, 1) // just the type byte
    }

    func testFrameRejectsOversizedPayload() {
        // Body = 1 (type) + payload must be <= maxPacketSize.
        // A payload of exactly maxPacketSize makes the body maxPacketSize+1 → rejected.
        let tooBig = Data(count: PacketFramer.maxPacketSize)
        XCTAssertThrowsError(try PacketFramer.frame(type: .videoFrame, payload: tooBig)) { error in
            guard case PacketFramer.FramingError.payloadTooLarge = error else {
                return XCTFail("Expected payloadTooLarge, got \(error)")
            }
        }

        // A payload one byte under the limit is accepted (body == maxPacketSize).
        let justFits = Data(count: PacketFramer.maxPacketSize - 1)
        XCTAssertNoThrow(try PacketFramer.frame(type: .videoFrame, payload: justFits))
    }

    func testUnframeValidPacket() throws {
        let payload = Data([0xAA, 0xBB])
        let packet = try PacketFramer.frame(type: .pong, payload: payload)

        let result = PacketFramer.unframe(buffer: packet)
        if case .success(let type, let resultPayload, let remaining) = result {
            XCTAssertEqual(type, .pong)
            XCTAssertEqual(resultPayload, payload)
            XCTAssertTrue(remaining.isEmpty)
        } else {
            XCTFail("Expected success, got \(result)")
        }
    }

    func testUnframeWithRemainingData() throws {
        let payload = Data([0x01])
        let packet = try PacketFramer.frame(type: .ping, payload: payload)
        let extra = Data([0xFF, 0xFE])
        let buffer = packet + extra

        let result = PacketFramer.unframe(buffer: buffer)
        if case .success(let type, let resultPayload, let remaining) = result {
            XCTAssertEqual(type, .ping)
            XCTAssertEqual(resultPayload, payload)
            XCTAssertEqual(remaining, extra)
        } else {
            XCTFail("Expected success")
        }
    }

    /// Guards against the slice-indexing bug: `unframe` must respect a non-zero
    /// `startIndex` (a Data slice), not assume 0-based indexing.
    func testUnframeHandlesSlicedBuffer() throws {
        let payload = Data([0x11, 0x22, 0x33])
        let packet = try PacketFramer.frame(type: .touchEvent, payload: payload)
        // Prefix with junk and slice past it so startIndex != 0.
        let prefixed = Data([0x99, 0x98, 0x97]) + packet
        let slice = prefixed.subdata(in: 3..<prefixed.count) // subdata re-bases to 0

        // Also exercise a true Data slice with a non-zero startIndex. Data's
        // SubSequence is Data itself, so this reaches unframe without re-basing.
        let trueSlice: Data = prefixed[3...]
        XCTAssertNotEqual(trueSlice.startIndex, 0)

        for buffer in [slice, trueSlice] {
            let result = PacketFramer.unframe(buffer: buffer)
            if case .success(let type, let resultPayload, _) = result {
                XCTAssertEqual(type, .touchEvent)
                XCTAssertEqual(resultPayload, payload)
            } else {
                XCTFail("Expected success for sliced buffer, got \(result)")
            }
        }
    }

    func testUnframeNeedMoreData() {
        let result = PacketFramer.unframe(buffer: Data([0x00, 0x00]))
        if case .needMoreData = result {
            // expected
        } else {
            XCTFail("Expected needMoreData")
        }
    }

    func testUnframeIncompletebody() {
        // Header says 10 bytes but only 2 bytes of body present
        var buffer = Data()
        var length = UInt32(10).bigEndian
        buffer.append(Data(bytes: &length, count: 4))
        buffer.append(Data([MessageType.ping.rawValue])) // 1 byte of body

        let result = PacketFramer.unframe(buffer: buffer)
        if case .needMoreData = result {
            // expected
        } else {
            XCTFail("Expected needMoreData")
        }
    }

    func testUnframeUnknownType() {
        var buffer = Data()
        var length = UInt32(1).bigEndian
        buffer.append(Data(bytes: &length, count: 4))
        buffer.append(Data([0xFF])) // unknown type

        let result = PacketFramer.unframe(buffer: buffer)
        if case .error = result {
            // expected
        } else {
            XCTFail("Expected error for unknown type")
        }
    }

    func testUnframeRejectsOversizedLength() {
        // Length field larger than maxPacketSize must be rejected, not buffered.
        var buffer = Data()
        var length = UInt32(PacketFramer.maxPacketSize + 1).bigEndian
        buffer.append(Data(bytes: &length, count: 4))
        buffer.append(Data([MessageType.videoFrame.rawValue]))

        let result = PacketFramer.unframe(buffer: buffer)
        if case .error = result {
            // expected
        } else {
            XCTFail("Expected error for oversized length")
        }
    }

    func testRoundTrip() throws {
        let types: [MessageType] = [.handshakeRequest, .videoFrame, .touchEvent, .bitrateUpdate]
        for type in types {
            let payload = Data((0..<50).map { UInt8($0) })
            let packet = try PacketFramer.frame(type: type, payload: payload)
            let result = PacketFramer.unframe(buffer: packet)

            if case .success(let unframedType, let unframedPayload, let remaining) = result {
                XCTAssertEqual(unframedType, type, "Type mismatch for \(type)")
                XCTAssertEqual(unframedPayload, payload, "Payload mismatch for \(type)")
                XCTAssertTrue(remaining.isEmpty)
            } else {
                XCTFail("Round-trip failed for \(type)")
            }
        }
    }

    /// Golden vector: framed TOUCH_EVENT (type 0x20).
    /// Expected: 0000001520 + the 20-byte touch payload.
    func testFramedTouchGoldenVector() throws {
        let event = TouchEvent(
            action: .move,
            x: 0.5,
            y: 0.25,
            pressure: 32768,
            pointerId: 1,
            timestampUs: 1_234_567_890_123_456
        )
        let payload = TouchSerializer.serialize(event)
        let framed = try PacketFramer.frame(type: .touchEvent, payload: payload)
        XCTAssertEqual(
            framed.hexString,
            "0000001520023F0000003E800000800001000462D53C8ABAC0"
        )
    }
}
