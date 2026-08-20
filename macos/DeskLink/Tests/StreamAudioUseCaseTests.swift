import XCTest
@testable import DeskLink

/// Drives the audio channel: start the tap when a client is present, announce the
/// format, stream PCM, and — critically — stop the tap when the client goes away so the
/// Mac's speakers come back.
final class StreamAudioUseCaseTests: XCTestCase {

    // MARK: - Doubles

    private final class SpyStreamServer: StreamServing, @unchecked Sendable {
        struct Sent: Equatable {
            let type: MessageType
            let data: Data
        }

        private let lock = NSLock()
        private var sentMessages: [Sent] = []
        private let connectionContinuation: AsyncStream<ClientConnection>.Continuation
        let clientConnections: AsyncStream<ClientConnection>

        init() {
            var continuation: AsyncStream<ClientConnection>.Continuation!
            clientConnections = AsyncStream { continuation = $0 }
            connectionContinuation = continuation
        }

        var sent: [Sent] { lock.withLock { sentMessages } }

        func connectClient() { connectionContinuation.yield(ClientConnection()) }
        func endConnections() { connectionContinuation.finish() }

        func start(port: UInt16, scope: ListenerScope) async throws {}
        func stop() async {}
        func send(data: Data, type: MessageType) async throws {
            lock.withLock { sentMessages.append(Sent(type: type, data: data)) }
        }
    }

    private final class StubAudioCapturer: AudioCapturing, @unchecked Sendable {
        private let lock = NSLock()
        private var startCount = 0
        private var stopCount = 0
        private var continuation: AsyncStream<AudioChunk>.Continuation?

        let format = AudioFormat(sampleRate: 48_000, channelCount: 2, encoding: .pcmSignedLittleEndian16)!
        var startCalls: Int { lock.withLock { startCount } }
        var stopCalls: Int { lock.withLock { stopCount } }

        func startCapture() async throws -> AudioCaptureSession {
            let (stream, continuation) = AsyncStream<AudioChunk>.makeStream()
            lock.withLock {
                startCount += 1
                self.continuation = continuation
            }
            return AudioCaptureSession(format: format, chunks: stream)
        }

        func stopCapture() async {
            let continuation = lock.withLock { () -> AsyncStream<AudioChunk>.Continuation? in
                stopCount += 1
                let captured = self.continuation
                self.continuation = nil
                return captured
            }
            continuation?.finish()
        }

        func emit(_ chunk: AudioChunk) {
            lock.withLock { continuation }?.yield(chunk)
        }

        func endStream() {
            lock.withLock { continuation }?.finish()
        }
    }

    private final class FailingAudioCapturer: AudioCapturing, @unchecked Sendable {
        private let lock = NSLock()
        private var stopCount = 0
        var stopCalls: Int { lock.withLock { stopCount } }

        func startCapture() async throws -> AudioCaptureSession {
            throw AudioCaptureError.noDefaultOutputDevice
        }

        func stopCapture() async {
            lock.withLock { stopCount += 1 }
        }
    }

    // MARK: - Helpers

    private func chunk(timestampUs: Int64, format: AudioFormat) -> AudioChunk {
        AudioChunk(pcm: Data(repeating: 0x7F, count: 8), timestampUs: timestampUs, format: format)
    }

    /// Waits until `condition` holds, so tests never depend on a fixed sleep.
    private func waitUntil(
        _ description: String,
        timeout: TimeInterval = 2,
        _ condition: @Sendable () -> Bool
    ) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline { XCTFail("timed out waiting for \(description)") ; return }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
    }

    // MARK: - Tests

    /// The tap must NOT be running before a client connects. Starting it early would
    /// mute the Mac's speakers while nothing is there to play the audio.
    func testTapIsNotStartedUntilAClientConnects() async throws {
        let server = SpyStreamServer()
        let capturer = StubAudioCapturer()
        let useCase = StreamAudioUseCase(capturer: capturer, streamServer: server)

        let task = Task { try? await useCase.execute() }
        try await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertEqual(capturer.startCalls, 0, "tap started with no client connected")

        server.endConnections()
        _ = await task.value
    }

    /// AUDIO_CONFIG must arrive before the first AUDIO_FRAME: a client that does not
    /// know the sample rate cannot start playback.
    func testSendsAudioConfigBeforeFirstFrame() async throws {
        let server = SpyStreamServer()
        let capturer = StubAudioCapturer()
        let useCase = StreamAudioUseCase(capturer: capturer, streamServer: server)

        let task = Task { try? await useCase.execute() }
        server.connectClient()
        try await waitUntil("AUDIO_CONFIG sent") { server.sent.contains { $0.type == .audioConfig } }

        capturer.emit(chunk(timestampUs: 1_000, format: capturer.format))
        try await waitUntil("AUDIO_FRAME sent") { server.sent.contains { $0.type == .audioFrame } }

        let types = server.sent.map(\.type)
        XCTAssertEqual(types.first, .audioConfig)
        XCTAssertEqual(server.sent.first?.data, capturer.format.serialize())

        server.endConnections()
        _ = await task.value
    }

    func testStreamsChunksAsAudioFrames() async throws {
        let server = SpyStreamServer()
        let capturer = StubAudioCapturer()
        let useCase = StreamAudioUseCase(capturer: capturer, streamServer: server)

        let task = Task { try? await useCase.execute() }
        server.connectClient()
        try await waitUntil("config sent") { server.sent.contains { $0.type == .audioConfig } }

        capturer.emit(chunk(timestampUs: 1_000, format: capturer.format))
        capturer.emit(chunk(timestampUs: 2_000, format: capturer.format))
        try await waitUntil("two frames sent") {
            server.sent.filter { $0.type == .audioFrame }.count == 2
        }

        let frames = server.sent.filter { $0.type == .audioFrame }
        let decoded = try frames.map { try AudioChunk.deserialize($0.data).get() }
        XCTAssertEqual(decoded.map(\.timestampUs), [1_000, 2_000])
        XCTAssertTrue(decoded.allSatisfy { $0.isConsistent(with: capturer.format) })

        server.endConnections()
        _ = await task.value
    }

    /// When the client disconnects the tap MUST be released. Leaving it running would
    /// keep the Mac silent with nothing playing the sound — the worst failure this
    /// feature can have.
    func testTapIsStoppedWhenConnectionsEnd() async throws {
        let server = SpyStreamServer()
        let capturer = StubAudioCapturer()
        let useCase = StreamAudioUseCase(capturer: capturer, streamServer: server)

        let task = Task { try? await useCase.execute() }
        server.connectClient()
        try await waitUntil("tap started") { capturer.startCalls == 1 }

        server.endConnections()
        _ = await task.value
        XCTAssertGreaterThanOrEqual(capturer.stopCalls, 1, "tap left running after client left")
    }

    /// Cancelling the task that runs the loop must also release the tap.
    ///
    /// This is the path `ServerCoordinator.stop()` actually takes: `TCPServer.stop()`
    /// deliberately does NOT finish its connection stream (so the server can be
    /// restarted), so the loop ends by cancellation, not by the stream completing.
    func testTapIsReleasedWhenTaskIsCancelled() async throws {
        let server = SpyStreamServer()
        let capturer = StubAudioCapturer()
        let useCase = StreamAudioUseCase(capturer: capturer, streamServer: server)

        let task = Task { try? await useCase.execute() }
        server.connectClient()
        try await waitUntil("tap started") { capturer.startCalls == 1 }

        task.cancel()
        _ = await task.value
        XCTAssertGreaterThanOrEqual(capturer.stopCalls, 1, "tap left running after cancellation")
    }

    /// A reconnect must re-announce AUDIO_CONFIG — the new client socket has not seen it.
    func testReconnectReannouncesConfig() async throws {
        let server = SpyStreamServer()
        let capturer = StubAudioCapturer()
        let useCase = StreamAudioUseCase(capturer: capturer, streamServer: server)

        let task = Task { try? await useCase.execute() }
        server.connectClient()
        try await waitUntil("first config") { server.sent.filter { $0.type == .audioConfig }.count == 1 }

        server.connectClient()
        try await waitUntil("second config") { server.sent.filter { $0.type == .audioConfig }.count == 2 }

        server.endConnections()
        _ = await task.value
    }

    /// If the tap cannot start, the tap must still be torn down and the loop must stay
    /// alive for the next connection rather than dying silently.
    func testCaptureStartFailureReleasesTapAndKeepsLoopAlive() async throws {
        let server = SpyStreamServer()
        let capturer = FailingAudioCapturer()
        let useCase = StreamAudioUseCase(capturer: capturer, streamServer: server)

        let task = Task { try? await useCase.execute() }
        server.connectClient()
        try await waitUntil("tap released after failure") { capturer.stopCalls >= 1 }
        XCTAssertTrue(server.sent.isEmpty, "nothing should be sent when capture failed")

        server.endConnections()
        _ = await task.value
    }
}
