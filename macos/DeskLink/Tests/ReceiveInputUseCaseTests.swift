import XCTest
@testable import DeskLink

final class ReceiveInputUseCaseTests: XCTestCase {

    private static let goldenEvent = TouchEvent(
        action: .move, x: 0.5, y: 0.25, pressure: 32768, pointerId: 1,
        timestampUs: 1_234_567_890_123_456
    )

    func testDecodeTouchEventFrame() {
        let payload = TouchSerializer.serialize(Self.goldenEvent)
        let frame = FrameAccumulator.Frame(type: .touchEvent, payload: payload)
        let events = ReceiveInputUseCase.decode(frame)
        XCTAssertEqual(events, [Self.goldenEvent])
    }

    func testDecodeTouchBatchFrame() {
        let events = [Self.goldenEvent, Self.goldenEvent]
        let payload = TouchSerializer.serializeBatch(events)
        let frame = FrameAccumulator.Frame(type: .touchBatch, payload: payload)
        XCTAssertEqual(ReceiveInputUseCase.decode(frame), events)
    }

    func testDecodeIgnoresNonInputFrames() {
        let frame = FrameAccumulator.Frame(type: .ping, payload: Data(count: 8))
        XCTAssertTrue(ReceiveInputUseCase.decode(frame).isEmpty)
    }

    func testDecodeMalformedTouchEventYieldsNothing() {
        let frame = FrameAccumulator.Frame(type: .touchEvent, payload: Data(count: 5)) // too short
        XCTAssertTrue(ReceiveInputUseCase.decode(frame).isEmpty)
    }

    /// End-to-end (in-memory): feed framed touch bytes through the use case and
    /// verify they reach the injector, including a frame split across chunks.
    func testRunForwardsDecodedEventsToInjector() async throws {
        let event1 = TouchEvent(action: .down, x: 0.1, y: 0.2, pressure: 1000, pointerId: 0, timestampUs: 1)
        let event2 = Self.goldenEvent

        let p1 = try PacketFramer.frame(type: .touchEvent, payload: TouchSerializer.serialize(event1))
        let p2 = try PacketFramer.frame(type: .touchEvent, payload: TouchSerializer.serialize(event2))
        let combined = p1 + p2

        // Split the stream mid-way through the first packet to exercise reassembly.
        let receiver = MockReceiver(chunks: [
            combined.subdata(in: 0..<3),
            combined.subdata(in: 3..<combined.count),
        ])
        let injector = SpyInjector()

        let useCase = ReceiveInputUseCase(receiver: receiver, injector: injector, displayID: 7)
        try await useCase.run()

        let received = await injector.records
        XCTAssertEqual(received.map(\.event), [event1, event2])
        XCTAssertTrue(received.allSatisfy { $0.displayID == 7 })
    }

    /// A SCROLL frame is decoded and forwarded to the injector as a scroll.
    func testRunForwardsScrollToInjector() async throws {
        let scroll = ScrollEvent(deltaX: 0.25, deltaY: -0.5)
        let framed = try PacketFramer.frame(type: .scroll, payload: ScrollSerializer.serialize(scroll))
        let receiver = MockReceiver(chunks: [framed])
        let injector = SpyInjector()

        let useCase = ReceiveInputUseCase(receiver: receiver, injector: injector, displayID: 7)
        try await useCase.run()

        let scrolls = await injector.scrolls
        XCTAssertEqual(scrolls, [scroll])
    }

    /// A POINTER_BUTTON frame is decoded and forwarded to the injector.
    func testRunForwardsPointerButtonToInjector() async throws {
        let down = PointerButtonEvent(button: .right, action: .down, x: 0.5, y: 0.25)
        let up = PointerButtonEvent(button: .right, action: .up, x: 0.5, y: 0.25)
        let framed = try PacketFramer.frame(type: .pointerButton, payload: PointerButtonSerializer.serialize(down))
            + PacketFramer.frame(type: .pointerButton, payload: PointerButtonSerializer.serialize(up))
        let receiver = MockReceiver(chunks: [framed])
        let injector = SpyInjector()

        let useCase = ReceiveInputUseCase(receiver: receiver, injector: injector, displayID: 7)
        try await useCase.run()

        let buttons = await injector.buttons
        XCTAssertEqual(buttons, [down, up])
    }
}

// MARK: - Test doubles

private struct Recorded: Equatable {
    let event: TouchEvent
    let displayID: UInt32
}

private final class MockReceiver: PacketReceiving, @unchecked Sendable {
    let receivedBytes: AsyncStream<Data>

    init(chunks: [Data]) {
        receivedBytes = AsyncStream { continuation in
            for chunk in chunks { continuation.yield(chunk) }
            continuation.finish()
        }
    }
}

private actor InjectorState {
    private(set) var records: [Recorded] = []
    private(set) var scrolls: [ScrollEvent] = []
    private(set) var buttons: [PointerButtonEvent] = []
    func record(_ e: TouchEvent, _ id: UInt32) { records.append(Recorded(event: e, displayID: id)) }
    func recordScroll(_ s: ScrollEvent) { scrolls.append(s) }
    func recordButton(_ b: PointerButtonEvent) { buttons.append(b) }
}

private final class SpyInjector: InputReceiving, @unchecked Sendable {
    private let state = InjectorState()

    var records: [Recorded] {
        get async { await state.records }
    }
    var scrolls: [ScrollEvent] {
        get async { await state.scrolls }
    }
    var buttons: [PointerButtonEvent] {
        get async { await state.buttons }
    }

    func startReceiving(port: UInt16) -> AsyncThrowingStream<TouchEvent, Error> {
        AsyncThrowingStream { $0.finish() }
    }
    func stopReceiving() async {}
    func injectEvent(_ event: TouchEvent, displayID: UInt32) async throws {
        await state.record(event, displayID)
    }
    func injectScroll(_ scroll: ScrollEvent, displayID: UInt32) async throws {
        await state.recordScroll(scroll)
    }
    func injectPointerButton(_ event: PointerButtonEvent, displayID: UInt32) async throws {
        await state.recordButton(event)
    }
}
