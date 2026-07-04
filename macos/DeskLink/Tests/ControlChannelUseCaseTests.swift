import XCTest
@testable import DeskLink

/// Verifies the Mac control channel answers the client handshake per spec §3.2:
/// HANDSHAKE_REQUEST → HANDSHAKE_RESPONSE, CONFIG_REQUEST → CONFIG_RESPONSE → START_STREAM.
final class ControlChannelUseCaseTests: XCTestCase {

    func testHandshakeRequestGetsAcceptedResponse() async throws {
        let server = RecordingServer()
        let useCase = ControlChannelUseCase(server: server, receiver: EmptyReceiver())

        let frame = FrameAccumulator.Frame(type: .handshakeRequest, payload: Self.handshakeRequestJSON())
        let clientInfo = try await useCase.process(frame, clientInfo: nil)

        XCTAssertNotNil(clientInfo)
        let sent = await server.sent
        XCTAssertEqual(sent.map(\.type), [.handshakeResponse])
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: sent[0].data) as? [String: Any])
        XCTAssertEqual(json["accepted"] as? Bool, true)
    }

    func testFullHandshakeThenConfigSendsResponsesAndStartStream() async throws {
        let server = RecordingServer()
        let streamStarts = StreamStartRecorder()
        let useCase = ControlChannelUseCase(
            server: server,
            receiver: EmptyReceiver(),
            onStreamStart: { config in await streamStarts.record(config) }
        )

        let hsFrame = FrameAccumulator.Frame(type: .handshakeRequest, payload: Self.handshakeRequestJSON())
        let clientInfo = try await useCase.process(hsFrame, clientInfo: nil)

        let cfgFrame = FrameAccumulator.Frame(type: .configRequest, payload: Self.configRequestJSON())
        _ = try await useCase.process(cfgFrame, clientInfo: clientInfo)

        let sent = await server.sent
        XCTAssertEqual(sent.map(\.type), [.handshakeResponse, .configResponse, .startStream])

        let started = await streamStarts.configs
        XCTAssertEqual(started.count, 1)
        XCTAssertEqual(started.first?.width, 1920)
        XCTAssertEqual(started.first?.height, 1200)
    }

    func testConfigRequestBeforeHandshakeIsIgnored() async throws {
        let server = RecordingServer()
        let useCase = ControlChannelUseCase(server: server, receiver: EmptyReceiver())

        let cfgFrame = FrameAccumulator.Frame(type: .configRequest, payload: Self.configRequestJSON())
        _ = try await useCase.process(cfgFrame, clientInfo: nil)

        let sent = await server.sent
        XCTAssertTrue(sent.isEmpty)
    }

    func testPingIsAnsweredWithPong() async throws {
        let server = RecordingServer()
        let useCase = ControlChannelUseCase(server: server, receiver: EmptyReceiver())

        let ts = ControlMessage.timestampPayload(millis: 1_700_000_000_000)
        let frame = FrameAccumulator.Frame(type: .ping, payload: ts)
        _ = try await useCase.process(frame, clientInfo: nil)

        let sent = await server.sent
        XCTAssertEqual(sent.map(\.type), [.pong])
        XCTAssertEqual(sent.first?.data, ts) // timestamp echoed back
    }

    // MARK: - Fixtures

    private static func handshakeRequestJSON() -> Data {
        let json: [String: Any] = [
            "protocolVersion": ProtocolConstants.protocolVersion,
            "clientName": "Test Client",
            "clientVersion": "1.0.0",
            "deviceModel": "Test Tablet",
            "screenWidth": 1920,
            "screenHeight": 1200,
            "maxFps": 60,
            "supportedCodecs": ["hevc", "h264"],
            "multiTouchMaxPoints": 10,
        ]
        return try! JSONSerialization.data(withJSONObject: json)
    }

    private static func configRequestJSON() -> Data {
        let json: [String: Any] = [
            "width": 1920,
            "height": 1200,
            "fps": 60,
            "codec": "hevc",
            "bitrateKbps": 20000,
        ]
        return try! JSONSerialization.data(withJSONObject: json)
    }
}

// MARK: - Test doubles

private struct SentMessage: Sendable {
    let data: Data
    let type: MessageType
}

private actor ServerState {
    private(set) var sent: [SentMessage] = []
    func append(_ message: SentMessage) { sent.append(message) }
}

private final class RecordingServer: StreamServing, @unchecked Sendable {
    private let state = ServerState()
    var sent: [SentMessage] { get async { await state.sent } }

    func start(port: UInt16) async throws {}
    func stop() async {}
    func send(data: Data, type: MessageType) async throws {
        await state.append(SentMessage(data: data, type: type))
    }
    var clientConnections: AsyncStream<ClientConnection> {
        AsyncStream { $0.finish() }
    }
}

private final class EmptyReceiver: PacketReceiving, @unchecked Sendable {
    let receivedBytes: AsyncStream<Data>
    init() { receivedBytes = AsyncStream { $0.finish() } }
}

private actor StreamStartRecorder {
    private(set) var configs: [DisplayConfig] = []
    func record(_ config: DisplayConfig) { configs.append(config) }
}
