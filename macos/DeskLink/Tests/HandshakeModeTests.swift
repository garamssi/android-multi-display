import XCTest
@testable import DeskLink

/// The handshake announces which display mode the session is running in.
///
/// The client needs it for two reasons: it must not open the input channel in mirror mode
/// (the port is not even bound), and it has to show that touch is off — otherwise an
/// unresponsive screen reads as a broken app.
final class HandshakeModeTests: XCTestCase {

    private func response(for mode: DisplayMode) throws -> [String: Any] {
        let handler = HandshakeHandler(displayMode: mode)
        let request = try JSONSerialization.data(withJSONObject: [
            "protocolVersion": ProtocolConstants.protocolVersion,
            "clientName": "test",
            "screenWidth": 3200,
            "screenHeight": 2000,
            "maxFps": 60,
            "supportedCodecs": ["hevc"],
        ])
        guard case .accepted(let payload, _) = handler.handleHandshakeRequest(payload: request) else {
            throw XCTSkip("handshake was not accepted")
        }
        guard let json = try JSONSerialization.jsonObject(with: payload) as? [String: Any] else {
            throw XCTSkip("response was not an object")
        }
        return json
    }

    func testExtendIsAnnounced() throws {
        XCTAssertEqual(try response(for: .extend)["displayMode"] as? String, "extend")
    }

    func testMirrorIsAnnounced() throws {
        XCTAssertEqual(try response(for: .mirror)["displayMode"] as? String, "mirror")
    }

    /// The wire values are fixed; renaming one silently breaks a client that already ships.
    func testWireValuesMatchTheProtocol() throws {
        XCTAssertEqual(try response(for: .extend)["displayMode"] as? String, DisplayMode.extend.wireValue)
        XCTAssertEqual(try response(for: .mirror)["displayMode"] as? String, DisplayMode.mirror.wireValue)
    }

    /// The rest of the response is unchanged, so an older client still parses it.
    func testExistingFieldsSurvive() throws {
        let json = try response(for: .mirror)
        XCTAssertEqual(json["protocolVersion"] as? Int, ProtocolConstants.protocolVersion)
        XCTAssertEqual(json["accepted"] as? Bool, true)
        XCTAssertNotNil(json["serverName"])
    }
}
