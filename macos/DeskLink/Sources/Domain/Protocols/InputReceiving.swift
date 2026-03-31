import Foundation

public protocol InputReceiving: Sendable {
    func startReceiving(port: UInt16) -> AsyncThrowingStream<TouchEvent, Error>
    func stopReceiving() async
    func injectEvent(_ event: TouchEvent, displayID: UInt32) async throws
}
