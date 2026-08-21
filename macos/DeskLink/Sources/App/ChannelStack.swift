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
    // Nil when the session refuses input (mirror mode): the port is not bound at all, so a
    // client that ignores the announced mode still cannot inject into the Mac's own screen.
    let inputPort: UInt16?
    // Nil on a stack that does not serve audio; binding a port nobody serves leaves the
    // client connected to silence with no error to report.
    let audioPort: UInt16?

    let requiresPairing: Bool

    let controlServer = TCPServer()
    var videoServer = TCPServer()
    var inputServer = TCPServer()
    let audioServer = TCPServer()

    init(
        kind: TransportKind,
        scope: ListenerScope,
        controlPort: UInt16,
        videoPort: UInt16,
        inputPort: UInt16?,
        audioPort: UInt16?,
        requiresPairing: Bool
    ) {
        self.kind = kind
        self.scope = scope
        self.controlPort = controlPort
        self.videoPort = videoPort
        self.inputPort = inputPort
        self.audioPort = audioPort
        self.requiresPairing = requiresPairing
    }

    func startListening() async throws {
        try await controlServer.start(port: controlPort, scope: scope)
        try await videoServer.start(port: videoPort, scope: scope)
        if let inputPort {
            try await inputServer.start(port: inputPort, scope: scope)
        }
        if let audioPort {
            try await audioServer.start(port: audioPort, scope: scope)
        }
    }

    func stop() async {
        await announceDisconnect()
        await inputServer.stop()
        await controlServer.stop()
        await videoServer.stop()
        await audioServer.stop()
    }

    // Tells the client the session is over instead of letting it infer that from silence.
    //
    // The client cannot see the socket close: the control channel runs through the `adb
    // reverse` tunnel, which does not deliver a FIN to the device when the mapping goes
    // away, so its blocking read simply stays blocked. Without this it waits out the
    // ping timeout before reconnecting -- measured at 2.2s of a 3.9s mode switch.
    //
    // Failure is ignored on purpose, and is not a swallowed error: the only reason this can
    // fail is that the client is already gone, which is the state the message announces.
    private func announceDisconnect() async {
        do {
            try await controlServer.send(data: Data(), type: .disconnect)
            Log.info(.server, "sent DISCONNECT on \(kind.displayName) control channel")
        } catch {
            Log.info(.server, "DISCONNECT not sent on \(kind.displayName): \(error)")
        }
    }
}
