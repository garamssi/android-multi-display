import Foundation

/// Which transport a `ChannelStack` serves. A value type so it can be captured by the
/// control channel's Sendable closures and resolved back to a stack on the MainActor.
enum TransportKind: Sendable {
    case usb
    case lan
}

/// One transport's set of DeskLink channels (control/video/input), bound on a single
/// interface scope and port range. The Mac runs two stacks at once so USB and Wi-Fi can
/// be served simultaneously without one stack's security model leaking into the other:
///
/// - USB: loopback, plaintext, no PIN, ports 7100-7102 (reachable via the adb-reverse
///   tunnel). Always present; the cable is the trust boundary.
/// - LAN: all interfaces, TLS, PIN pairing, ports 7110-7112. Present only when the user
///   opts into Wi-Fi serving.
///
/// This split is the fix for USB failing whenever Wi-Fi was on: a single shared listener
/// had to be either plaintext-no-PIN or TLS-PIN for every client, so enabling Wi-Fi
/// forced the loopback USB path through TLS+PIN and broke it. Separating by listener also
/// means the transport is inferred from which socket a client reached — which a client
/// cannot forge, unlike a self-declared USB/Wi-Fi flag.
///
/// `videoServer`/`inputServer` are `var` because a reconnect must recreate them (their
/// `AsyncStream`s can be consumed only once); recreating updates the stack's own fields
/// so streaming never targets a stale, stopped server. The control server lives for the
/// whole session.
@MainActor
final class ChannelStack {
    let kind: TransportKind
    let scope: ListenerScope
    let controlPort: UInt16
    let videoPort: UInt16
    let inputPort: UInt16

    /// LAN only: the control channel must complete PIN pairing before the handshake.
    let requiresPairing: Bool

    let controlServer = TCPServer()
    var videoServer = TCPServer()
    var inputServer = TCPServer()

    init(
        kind: TransportKind,
        scope: ListenerScope,
        controlPort: UInt16,
        videoPort: UInt16,
        inputPort: UInt16,
        requiresPairing: Bool
    ) {
        self.kind = kind
        self.scope = scope
        self.controlPort = controlPort
        self.videoPort = videoPort
        self.inputPort = inputPort
        self.requiresPairing = requiresPairing
    }

    /// Binds all three listeners. Called once per `start()`; a bind failure propagates so
    /// the coordinator can roll the whole session back.
    func startListening() async throws {
        try await controlServer.start(port: controlPort, scope: scope)
        try await videoServer.start(port: videoPort, scope: scope)
        try await inputServer.start(port: inputPort, scope: scope)
    }

    /// Stops all three listeners for this stack.
    func stop() async {
        await inputServer.stop()
        await controlServer.stop()
        await videoServer.stop()
    }
}
