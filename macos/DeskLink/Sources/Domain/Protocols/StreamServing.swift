import Foundation

/// Which network scope a server listens on. This is an abstract link property (who can
/// reach the socket), not a framework detail, so it lives in the domain: the data layer
/// maps it to the concrete `NWParameters`.
public enum ListenerScope: Sendable {
    /// Loopback only — reachable via `adb reverse` (USB). No local-network exposure.
    case loopback
    /// All interfaces — additionally reachable from the local network (Wi-Fi/LAN). The
    /// loopback path (USB) still works, since all-interface binding includes loopback.
    /// Plaintext and unauthenticated in this phase, so it is opt-in only.
    case localNetwork
}

public protocol StreamServing: Sendable {
    func start(port: UInt16, scope: ListenerScope) async throws
    func stop() async
    func send(data: Data, type: MessageType) async throws
    var clientConnections: AsyncStream<ClientConnection> { get }
}

public struct ClientConnection: Sendable {
    public let id: UUID
    public let connectedAt: Date

    public init(id: UUID = UUID(), connectedAt: Date = Date()) {
        self.id = id
        self.connectedAt = connectedAt
    }
}
