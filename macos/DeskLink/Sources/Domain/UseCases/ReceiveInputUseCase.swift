import Foundation

public final class ReceiveInputUseCase: Sendable {
    private let receiver: any PacketReceiving
    private let injector: any InputReceiving
    private let displayID: UInt32

    public init(receiver: any PacketReceiving, injector: any InputReceiving, displayID: UInt32) {
        self.receiver = receiver
        self.injector = injector
        self.displayID = displayID
    }

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
            return []
        }
    }
}
