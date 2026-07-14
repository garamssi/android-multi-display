import Foundation

public enum ConnectionSnapshot: Sendable {
    case stopped
    case waiting
    case connected(ClientInfo, DisplayConfig, TransportKind)
}
