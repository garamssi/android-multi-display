import Foundation

public enum TransportKind: Sendable {
    case usb
    case lan

    public var displayName: String {
        switch self {
        case .usb: return "USB"
        case .lan: return "Wi-Fi"
        }
    }
}

// videoServer/inputServer are var: a reconnect must recreate them (an AsyncStream can be consumed only once).
@MainActor
final class ChannelStack {
    let kind: TransportKind
    let scope: ListenerScope
    let controlPort: UInt16
    let videoPort: UInt16
    let inputPort: UInt16

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

    func startListening() async throws {
        try await controlServer.start(port: controlPort, scope: scope)
        try await videoServer.start(port: videoPort, scope: scope)
        try await inputServer.start(port: inputPort, scope: scope)
    }

    func stop() async {
        await inputServer.stop()
        await controlServer.stop()
        await videoServer.stop()
    }
}
