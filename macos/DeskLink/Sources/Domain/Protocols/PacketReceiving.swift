import Foundation

/// Abstraction over a channel that receives raw bytes from the client.
/// Implemented by the TCP transport; the async stream yields raw `Data` chunks
/// exactly as they arrive from the socket (which may split or coalesce frames).
public protocol PacketReceiving: Sendable {
    /// Raw bytes received on the channel, in arrival order. Finishes when the
    /// underlying connection closes.
    var receivedBytes: AsyncStream<Data> { get }
}
