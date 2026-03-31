import Foundation

public enum ConnectionState: Sendable, Equatable {
    case disconnected
    case listening
    case handshaking(clientName: String)
    case negotiating(config: DisplayConfig)
    case connected(config: DisplayConfig, clientName: String)
    case error(ConnectionError)
}
