import Foundation

public protocol StreamServing: Sendable {
    func start(port: UInt16) async throws
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
