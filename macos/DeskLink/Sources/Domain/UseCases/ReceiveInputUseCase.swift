import Foundation

/// Reads framed packets from the Input channel, decodes TOUCH_EVENT (0x20) and
/// TOUCH_BATCH (0x21) via `TouchDeserializer`, and forwards each decoded event to
/// the injector (S-L10).
///
/// The byte source is abstracted via `PacketReceiving` and framing is handled by
/// `FrameAccumulator`, so the whole pipeline is unit-testable by feeding synthetic
/// byte chunks — no real socket required.
public final class ReceiveInputUseCase: Sendable {
    private let receiver: any PacketReceiving
    private let injector: any InputReceiving
    private let displayID: UInt32

    public init(receiver: any PacketReceiving, injector: any InputReceiving, displayID: UInt32) {
        self.receiver = receiver
        self.injector = injector
        self.displayID = displayID
    }

    /// Runs the receive loop until the input channel closes. Malformed touch
    /// payloads are skipped; framing errors terminate the loop (the connection is
    /// corrupt). Injection errors are surfaced to `onInjectError` (if provided)
    /// and otherwise ignored so a single failed event does not tear down input.
    public func run(onInjectError: (@Sendable (Error) -> Void)? = nil) async throws {
        var accumulator = FrameAccumulator()

        for await chunk in receiver.receivedBytes {
            let frames = try accumulator.append(chunk)
            for frame in frames {
                switch frame.type {
                case .scroll:
                    guard let scroll = ScrollDeserializer.deserialize(data: frame.payload) else { continue }
                    do {
                        try await injector.injectScroll(scroll, displayID: displayID)
                    } catch {
                        onInjectError?(error)
                    }
                case .pointerButton:
                    guard let button = PointerButtonDeserializer.deserialize(data: frame.payload) else { continue }
                    do {
                        try await injector.injectPointerButton(button, displayID: displayID)
                    } catch {
                        onInjectError?(error)
                    }
                default:
                    for event in Self.decode(frame) {
                        do {
                            try await injector.injectEvent(event, displayID: displayID)
                        } catch {
                            onInjectError?(error)
                        }
                    }
                }
            }
        }
    }

    /// Decodes a single input-channel frame into zero or more touch events.
    /// Exposed for testing.
    static func decode(_ frame: FrameAccumulator.Frame) -> [TouchEvent] {
        switch frame.type {
        case .touchEvent:
            if let event = TouchDeserializer.deserialize(data: frame.payload) {
                return [event]
            }
            return []
        case .touchBatch:
            return TouchDeserializer.deserializeBatch(data: frame.payload)
        default:
            // Non-input frames on the input channel are ignored.
            return []
        }
    }
}
