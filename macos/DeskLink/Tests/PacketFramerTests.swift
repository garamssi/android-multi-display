import XCTest
@testable import DeskLink

final class PacketFramerTests: XCTestCase {

    func testFrameCreatesValidPacket() {
        let payload = Data([0x01, 0x02, 0x03])
        let packet = PacketFramer.frame(type: .ping, payload: payload)

        // Length = 1 (type) + 3 (payload) = 4
        // Total = 4 (length header) + 1 (type) + 3 (payload) = 8
        XCTAssertEqual(packet.count, 8)

        // First 4 bytes: length in big-endian
        let length = packet.withUnsafeBytes { $0.load(fromByteOffset: 0, as: UInt32.self).bigEndian }
        XCTAssertEqual(length, 4) // type + payload

        // 5th byte: type
        XCTAssertEqual(packet[4], MessageType.ping.rawValue)

        // Remaining: payload
        XCTAssertEqual(packet.subdata(in: 5..<8), payload)
    }

    func testFrameEmptyPayload() {
        let packet = PacketFramer.frame(type: .disconnect, payload: Data())
        XCTAssertEqual(packet.count, 5) // 4 header + 1 type + 0 payload

        let length = packet.withUnsafeBytes { $0.load(fromByteOffset: 0, as: UInt32.self).bigEndian }
        XCTAssertEqual(length, 1) // just the type byte
    }

    func testUnframeValidPacket() {
        let payload = Data([0xAA, 0xBB])
        let packet = PacketFramer.frame(type: .pong, payload: payload)

        let result = PacketFramer.unframe(buffer: packet)
        if case .success(let type, let resultPayload, let remaining) = result {
            XCTAssertEqual(type, .pong)
            XCTAssertEqual(resultPayload, payload)
            XCTAssertTrue(remaining.isEmpty)
        } else {
            XCTFail("Expected success, got \(result)")
        }
    }

    func testUnframeWithRemainingData() {
        let payload = Data([0x01])
        let packet = PacketFramer.frame(type: .ping, payload: payload)
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

    func testRoundTrip() {
        let types: [MessageType] = [.handshakeRequest, .videoFrame, .touchEvent, .bitrateUpdate]
        for type in types {
            let payload = Data((0..<50).map { UInt8($0) })
            let packet = PacketFramer.frame(type: type, payload: payload)
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
}
