import Foundation
import ScreenCaptureKit
import XCTest
@testable import DeskLink

/// Regression: pressing Stop Sharing in the system capture indicator must end the whole
/// session.
///
/// Previously it only ended the video stream. The server kept listening, the audio tap
/// kept streaming sound to the tablet, and the menu still read "Stop Server" with no way
/// to start sharing again — so the picture froze while the sound played on.
final class StartStreamingUseCaseStopTests: XCTestCase {

    private static let testSource = StreamSource(
        displayID: 1,
        captureWidth: 1600,
        captureHeight: 1000,
        acceptsInput: true
    )

    // MARK: - Doubles

    private final class StubCapturer: ScreenCapturing, @unchecked Sendable {
        private let error: Error
        private let lock = NSLock()
        private var stopCount = 0

        init(failingWith error: Error) { self.error = error }

        var stopCalls: Int { lock.withLock { stopCount } }

        func startCapture(source: StreamSource, fps: Int) -> AsyncThrowingStream<VideoFrame, Error> {
            let error = self.error
            return AsyncThrowingStream { continuation in
                continuation.finish(throwing: error)
            }
        }

        func stopCapture() async { lock.withLock { stopCount += 1 } }
    }

    private final class StubEncoder: VideoEncoding, @unchecked Sendable {
        func configure(config: DisplayConfig) async throws {}
        func encode(frame: VideoFrame) async throws -> EncodedFrame {
            EncodedFrame(data: Data(), timestampUs: 0, isKeyframe: false, frameNumber: 0)
        }
        func updateBitrate(kbps: Int) async {}
        func forceKeyframe() async {}
        var codecConfigData: Data? { get async { nil } }
    }

    private final class StubDisplayManager: VirtualDisplayManaging, @unchecked Sendable {
        func createDisplay(config: DisplayConfig) async throws {}
        func destroyDisplay() async {}
        var displayID: UInt32 { 1 }
        var activeResolution: (width: Int, height: Int)? { nil }
    }

    private final class StubStreamServer: StreamServing, @unchecked Sendable {
        private let continuation: AsyncStream<ClientConnection>.Continuation
        let clientConnections: AsyncStream<ClientConnection>

        init() {
            var continuation: AsyncStream<ClientConnection>.Continuation!
            clientConnections = AsyncStream { continuation = $0 }
            self.continuation = continuation
        }

        func connectClient() { continuation.yield(ClientConnection()) }
        func endConnections() { continuation.finish() }

        func start(port: UInt16, scope: ListenerScope) async throws {}
        func stop() async {}
        func send(data: Data, type: MessageType) async throws {}
    }

    private func makeUseCase(
        capturer: StubCapturer,
        server: StubStreamServer,
        onEnded: @escaping @Sendable () async -> Void
    ) -> StartStreamingUseCase {
        StartStreamingUseCase(
            displayManager: StubDisplayManager(),
            screenCapturer: capturer,
            encoder: StubEncoder(),
            streamServer: server,
            onSharingEndedByUser: onEnded
        )
    }

    private func waitUntil(
        _ description: String,
        timeout: TimeInterval = 2,
        _ condition: @Sendable () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline { return XCTFail("timed out waiting for \(description)") }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
    }

    // MARK: - Tests

    func testUserStoppingSharingEndsTheSession() async {
        let userStopped = NSError(domain: SCStreamErrorDomain, code: -3817)
        let capturer = StubCapturer(failingWith: userStopped)
        let server = StubStreamServer()
        let ended = OSAllocatedUnfairLockBox(false)

        let useCase = makeUseCase(capturer: capturer, server: server) {
            ended.value = true
        }
        let source = Self.testSource
        let task = Task { try? await useCase.execute(config: DisplayConfig(), source: source) }
        server.connectClient()

        await waitUntil("session ended") { ended.value }
        XCTAssertTrue(ended.value, "Stop Sharing did not end the session")

        server.endConnections()
        _ = await task.value
    }

    /// A broken stream is NOT a user stop: the server must keep listening so a reconnect
    /// can rebuild the pipeline.
    func testTransientFailureDoesNotEndTheSession() async {
        let failure = NSError(domain: SCStreamErrorDomain, code: -3815) // noCaptureSource
        let capturer = StubCapturer(failingWith: failure)
        let server = StubStreamServer()
        let ended = OSAllocatedUnfairLockBox(false)

        let useCase = makeUseCase(capturer: capturer, server: server) {
            ended.value = true
        }
        let source = Self.testSource
        let task = Task { try? await useCase.execute(config: DisplayConfig(), source: source) }
        server.connectClient()

        await waitUntil("capture torn down") { capturer.stopCalls >= 1 }
        XCTAssertFalse(ended.value, "a recoverable failure must not end the whole session")

        server.endConnections()
        _ = await task.value
    }
}

/// Minimal locked box, so the test doubles can record state without importing a
/// synchronization helper the package does not otherwise use.
final class OSAllocatedUnfairLockBox<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Value

    init(_ value: Value) { stored = value }

    var value: Value {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }
}
