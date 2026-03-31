import Foundation

public final class HandleTouchUseCase: Sendable {
    private let inputReceiver: any InputReceiving

    public init(inputReceiver: any InputReceiving) {
        self.inputReceiver = inputReceiver
    }

    public func startReceiving(displayID: UInt32) -> AsyncThrowingStream<TouchEvent, Error> {
        inputReceiver.startReceiving(port: ProtocolConstants.portInput)
    }

    public func inject(_ event: TouchEvent, displayID: UInt32) async throws {
        try await inputReceiver.injectEvent(event, displayID: displayID)
    }

    public func stop() async {
        await inputReceiver.stopReceiving()
    }
}
