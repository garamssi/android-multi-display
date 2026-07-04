import XCTest
@testable import DeskLink

final class HandshakeHandlerTests: XCTestCase {
    let handler = HandshakeHandler()

    func testAcceptValidHandshakeRequest() {
        let json: [String: Any] = [
            "protocolVersion": 1,
            "clientName": "DeskLink Android",
            "clientVersion": "1.0.0",
            "deviceModel": "Test Device",
            "screenWidth": 2560,
            "screenHeight": 1600,
            "maxFps": 120,
            "supportedCodecs": ["hevc", "h264"],
            "multiTouchMaxPoints": 10,
        ]
        let data = try! JSONSerialization.data(withJSONObject: json)
        let result = handler.handleHandshakeRequest(payload: data)

        if case .accepted(let response, let clientInfo) = result {
            XCTAssertFalse(response.isEmpty)
            XCTAssertEqual(clientInfo.clientName, "DeskLink Android")
            XCTAssertEqual(clientInfo.screenWidth, 2560)
            XCTAssertEqual(clientInfo.maxFps, 120)

            // Verify response JSON contains accepted: true
            let responseJson = try! JSONSerialization.jsonObject(with: response) as! [String: Any]
            XCTAssertEqual(responseJson["accepted"] as? Bool, true)
        } else {
            XCTFail("Expected accepted result")
        }
    }

    func testRejectWrongProtocolVersion() {
        let json: [String: Any] = ["protocolVersion": 999]
        let data = try! JSONSerialization.data(withJSONObject: json)
        let result = handler.handleHandshakeRequest(payload: data)

        if case .rejected(let response, _) = result {
            let responseJson = try! JSONSerialization.jsonObject(with: response) as! [String: Any]
            XCTAssertEqual(responseJson["accepted"] as? Bool, false)
            XCTAssertNotNil(responseJson["rejectReason"])
        } else {
            XCTFail("Expected rejected result")
        }
    }

    func testRejectInvalidPayload() {
        let data = Data("not json".utf8)
        let result = handler.handleHandshakeRequest(payload: data)

        if case .rejected = result {
            // expected
        } else {
            XCTFail("Expected rejected for invalid JSON")
        }
    }

    func testConfigNegotiationClampsToClientMax() throws {
        let clientInfo = ClientInfo(
            clientName: "Test", clientVersion: "1.0", deviceModel: "Test",
            screenWidth: 1920, screenHeight: 1200, maxFps: 60,
            supportedCodecs: ["hevc"], multiTouchMaxPoints: 10
        )

        // Request higher than client supports
        let requestJson: [String: Any] = [
            "width": 2560, "height": 1600, "fps": 120, "codec": "hevc", "bitrateKbps": 50000
        ]
        let requestData = try JSONSerialization.data(withJSONObject: requestJson)
        let (_, config) = try handler.handleConfigRequest(payload: requestData, clientInfo: clientInfo)

        // Should be clamped to client's max
        XCTAssertEqual(config.width, 1920) // clamped from 2560
        XCTAssertEqual(config.height, 1200) // clamped from 1600
        XCTAssertEqual(config.fps, 60) // clamped from 120
        XCTAssertEqual(config.bitrateKbps, 40_000) // clamped from 50000 to max 40000
    }

    // MARK: - Validation (S-M4 / S-M5)

    private func clientInfo(supportedCodecs: [String]) -> ClientInfo {
        ClientInfo(
            clientName: "Test", clientVersion: "1.0", deviceModel: "Test",
            screenWidth: 1920, screenHeight: 1200, maxFps: 60,
            supportedCodecs: supportedCodecs, multiTouchMaxPoints: 10
        )
    }

    func testRejectsCodecNotInSupportedList() throws {
        let info = clientInfo(supportedCodecs: ["hevc"]) // no h264
        let requestJson: [String: Any] = [
            "width": 1920, "height": 1200, "fps": 60, "codec": "h264", "bitrateKbps": 20000
        ]
        let data = try JSONSerialization.data(withJSONObject: requestJson)
        XCTAssertThrowsError(try handler.handleConfigRequest(payload: data, clientInfo: info)) { error in
            XCTAssertEqual(error as? ConnectionError, .codecNotSupported)
            XCTAssertEqual((error as? ConnectionError)?.rawValue, 1104)
        }
    }

    func testRejectsUnknownCodec() throws {
        let info = clientInfo(supportedCodecs: ["hevc", "h264", "vp9"])
        let requestJson: [String: Any] = [
            "width": 1920, "height": 1200, "fps": 60, "codec": "vp9", "bitrateKbps": 20000
        ]
        let data = try JSONSerialization.data(withJSONObject: requestJson)
        XCTAssertThrowsError(try handler.handleConfigRequest(payload: data, clientInfo: info)) { error in
            // vp9 is not a codec DeskLink can produce, even if advertised.
            XCTAssertEqual(error as? ConnectionError, .codecNotSupported)
        }
    }

    func testAcceptsSupportedH264() throws {
        let info = clientInfo(supportedCodecs: ["hevc", "h264"])
        let requestJson: [String: Any] = [
            "width": 1280, "height": 720, "fps": 60, "codec": "h264", "bitrateKbps": 10000
        ]
        let data = try JSONSerialization.data(withJSONObject: requestJson)
        let (_, config) = try handler.handleConfigRequest(payload: data, clientInfo: info)
        XCTAssertEqual(config.codec, .h264)
    }

    func testRejectsZeroWidth() throws {
        let info = clientInfo(supportedCodecs: ["hevc"])
        let requestJson: [String: Any] = [
            "width": 0, "height": 1200, "fps": 60, "codec": "hevc", "bitrateKbps": 20000
        ]
        let data = try JSONSerialization.data(withJSONObject: requestJson)
        XCTAssertThrowsError(try handler.handleConfigRequest(payload: data, clientInfo: info)) { error in
            XCTAssertEqual(error as? ConnectionError, .configInvalid)
            XCTAssertEqual((error as? ConnectionError)?.rawValue, 1400)
        }
    }

    func testRejectsNegativeHeight() throws {
        let info = clientInfo(supportedCodecs: ["hevc"])
        let requestJson: [String: Any] = [
            "width": 1920, "height": -1, "fps": 60, "codec": "hevc", "bitrateKbps": 20000
        ]
        let data = try JSONSerialization.data(withJSONObject: requestJson)
        XCTAssertThrowsError(try handler.handleConfigRequest(payload: data, clientInfo: info)) { error in
            XCTAssertEqual(error as? ConnectionError, .configInvalid)
        }
    }

    func testErrorMessageMapsCodeAndText() {
        let payload = handler.makeErrorMessage(.codecNotSupported)
        let json = try! JSONSerialization.jsonObject(with: payload) as! [String: Any]
        XCTAssertEqual(json["code"] as? Int, 1104)
        XCTAssertNotNil(json["message"] as? String)
    }
}
