import XCTest
@testable import DeskLink

final class MessageTypeTests: XCTestCase {

    func testControlChannelMessageTypes() {
        XCTAssertEqual(MessageType.handshakeRequest.rawValue, 0x01)
        XCTAssertEqual(MessageType.handshakeResponse.rawValue, 0x02)
        XCTAssertEqual(MessageType.configRequest.rawValue, 0x03)
        XCTAssertEqual(MessageType.configResponse.rawValue, 0x04)
        XCTAssertEqual(MessageType.startStream.rawValue, 0x05)
        XCTAssertEqual(MessageType.stopStream.rawValue, 0x06)
        XCTAssertEqual(MessageType.ping.rawValue, 0x07)
        XCTAssertEqual(MessageType.pong.rawValue, 0x08)
        XCTAssertEqual(MessageType.error.rawValue, 0x09)
        XCTAssertEqual(MessageType.disconnect.rawValue, 0x0A)
        XCTAssertEqual(MessageType.bitrateUpdate.rawValue, 0x0B)
        XCTAssertEqual(MessageType.configUpdate.rawValue, 0x0C)
    }

    func testVideoChannelMessageTypes() {
        XCTAssertEqual(MessageType.videoFrame.rawValue, 0x10)
        XCTAssertEqual(MessageType.videoConfig.rawValue, 0x11)
        XCTAssertEqual(MessageType.keyframeRequest.rawValue, 0x12)
    }

    func testInputChannelMessageTypes() {
        XCTAssertEqual(MessageType.touchEvent.rawValue, 0x20)
        XCTAssertEqual(MessageType.touchBatch.rawValue, 0x21)
    }
}
