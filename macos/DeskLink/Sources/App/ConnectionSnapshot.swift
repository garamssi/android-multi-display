import Foundation

/// Single source of truth for the server's connection state, owned by
/// `ServerCoordinator` and mirrored by the UI.
///
/// This replaces the previous trio of independent callbacks
/// (`onStatusChange` / `onClientConnected` / `onClientDisconnected`) plus the scattered
/// boolean/set state (`isRunning`, `connectedTransports`, a debounce task) that let the
/// UI and the coordinator disagree about whether a client was connected.
///
/// Crucially, `.connected` is entered when a client completes handshake + config
/// negotiation, and it ends only when the client's VIDEO stream actually dies — NOT when
/// the control channel's keep-alive heartbeat lapses. That heartbeat was a fragile 3s
/// timer that false-expired during the connect burst (heavy decoder init on the tablet),
/// which flipped a healthy, still-streaming session back to "waiting" and wiped the live
/// stats. Video liveness is the real signal that a client is present and working.
enum ConnectionSnapshot: Sendable {
    /// Server not running (no listeners).
    case stopped
    /// Server running and listening, but no client session is active.
    case waiting
    /// A client finished negotiation and its video session is live. Carries the
    /// handshake device info, negotiated config, and the transport it connected over.
    case connected(ClientInfo, DisplayConfig, TransportKind)
}
