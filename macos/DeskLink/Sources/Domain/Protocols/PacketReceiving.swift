import Foundation

public protocol PacketReceiving: Sendable {
    var receivedBytes: AsyncStream<Data> { get }
}
