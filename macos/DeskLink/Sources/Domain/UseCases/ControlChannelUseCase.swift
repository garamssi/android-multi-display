import Foundation

/// Drives the Control-channel liveness protocol (S-L10):
/// - Responds to inbound PING (0x07) with PONG (0x08), echoing the timestamp.
/// - Records inbound PONG (0x08) to keep the connection alive.
/// - Sends a PING every `PING_INTERVAL` (1s).
/// - Treats > `PING_TIMEOUT` (3s) of PONG silence as a disconnect.
///
/// Timings come from `ProtocolConstants`. The liveness bookkeeping is delegated to
/// the injectable-clock `ConnectionMonitor`, so the timeout behaviour is tested
/// deterministically with a `VirtualClock` (no sleeping).
public final class ControlChannelUseCase: Sendable {
    private let server: any StreamServing
    private let receiver: any PacketReceiving
    private let monitor: ConnectionMonitor
    private let handshakeHandler = HandshakeHandler()

    /// LAN pairing gate. With no key (USB) it is pre-authenticated and issues no
    /// challenge, so the flow is unchanged. With a key (LAN) the client must pass the
    /// challenge-response before the handshake is accepted.
    private let authGate: AuthGate

    /// Invoked once config negotiation succeeds, with the negotiated config, so the
    /// composition root can (re)start the video pipeline. Defaults to a no-op for
    /// callers/tests that manage streaming separately.
    private let onStreamStart: @Sendable (DisplayConfig) async throws -> Void

    /// Observability hook: fired after START_STREAM is sent for a freshly negotiated
    /// client, carrying its handshake `ClientInfo` and negotiated `DisplayConfig`.
    /// Purely informational — does not affect the streaming/liveness flow. No-op default.
    private let onClientConnected: @Sendable (ClientInfo, DisplayConfig) async -> Void

    /// Observability hook: fired when a connected client's liveness lapses and the
    /// channel loops back to await the next client. No-op default.
    private let onClientDisconnected: @Sendable () async -> Void

    public init(
        server: any StreamServing,
        receiver: any PacketReceiving,
        monitor: ConnectionMonitor = ConnectionMonitor(),
        authKey: Data? = nil,
        onStreamStart: @escaping @Sendable (DisplayConfig) async throws -> Void = { _ in },
        onClientConnected: @escaping @Sendable (ClientInfo, DisplayConfig) async -> Void = { _, _ in },
        onClientDisconnected: @escaping @Sendable () async -> Void = { }
    ) {
        self.server = server
        self.receiver = receiver
        self.monitor = monitor
        self.authGate = AuthGate(key: authKey)
        self.onStreamStart = onStreamStart
        self.onClientConnected = onClientConnected
        self.onClientDisconnected = onClientDisconnected
    }

    /// Starts the control channel: launches the PING sender and the receive loop
    /// concurrently. Returns (throws `ConnectionError.lost`) when the peer is
    /// considered disconnected or the channel closes.
    public func run() async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask { [self] in try await runReceiveLoop() }
            group.addTask { [self] in try await runKeepAlive() }

            // The first task to finish/throw tears the group down.
            try await group.next()
            group.cancelAll()
        }
    }

    /// Drives keep-alive for each client that connects, one at a time.
    ///
    /// Waiting for a client before pinging avoids the 3s liveness window elapsing
    /// while the server merely idle-listens. Crucially, when a client stops
    /// responding, `runPingLoop` returns (its PONG-timeout is swallowed here) and we
    /// loop back to await the NEXT client — so a reconnect, or a reinstalled app,
    /// is handled WITHOUT restarting the server. The receive loop keeps running on
    /// the same byte stream throughout, so the new client's handshake is processed.
    private func runKeepAlive() async throws {
        for await _ in server.clientConnections {
            // LAN pairing: challenge the client before its handshake is accepted. USB
            // (no key) returns nil and skips this — the flow is unchanged.
            if let challenge = await authGate.beginChallenge() {
                try? await server.send(data: challenge, type: .authChallenge)
            }
            // Fresh liveness window for this client, then ping until it drops.
            monitor.recordPong()
            try? await runPingLoop() // returns on PONG-timeout; then await next client
            if Task.isCancelled { break }
            // The client dropped (PONG-timeout) but the server keeps listening —
            // surface it for the UI before awaiting the next connection.
            await onClientDisconnected()
        }
    }

    /// Sends a PING every interval and fails on PONG-timeout. Uses short sleeps
    /// and re-checks the monitor rather than blocking for the whole interval, so
    /// cancellation is responsive.
    private func runPingLoop() async throws {
        // Poll at a fraction of the interval for timely timeout detection.
        let tickMs = max(50, Int64(ProtocolConstants.pingInterval) / 4)

        while true {
            try Task.checkCancellation()

            if monitor.isTimedOut() {
                throw ConnectionError.lost
            }

            if monitor.shouldSendPing() {
                let payload = ControlMessage.timestampPayload(millis: Self.nowMillis())
                try await server.send(data: payload, type: .ping)
            }

            try await Task.sleep(nanoseconds: UInt64(tickMs) * 1_000_000)
        }
    }

    /// Reads control frames and handles handshake, config negotiation, and PING/PONG.
    private func runReceiveLoop() async throws {
        var accumulator = FrameAccumulator()
        var clientInfo: ClientInfo?

        for await chunk in receiver.receivedBytes {
            // Any inbound data means the peer is alive; reset the liveness window.
            monitor.recordPong()
            do {
                let frames = try accumulator.append(chunk)
                for frame in frames {
                    clientInfo = try await process(frame, clientInfo: clientInfo)
                }
            } catch is CancellationError {
                throw CancellationError() // let a real cancellation tear the loop down
            } catch {
                // A previous client may have dropped mid-frame (leaving partial bytes
                // that corrupt the next client's framing), or a send may have failed
                // because the peer went away. Resync instead of tearing down the whole
                // control channel, and drop any half-finished negotiation state so the
                // next/reconnecting client starts clean.
                accumulator = FrameAccumulator()
                clientInfo = nil
            }
        }
        // Byte stream finished — the server itself stopped.
        throw ConnectionError.lost
    }

    /// Handles one inbound control frame and returns the (possibly updated) client
    /// info so the caller can thread negotiation state across frames. Implements the
    /// handshake flow per spec §3.2: HANDSHAKE_REQUEST → HANDSHAKE_RESPONSE, then
    /// CONFIG_REQUEST → CONFIG_RESPONSE → START_STREAM. `internal` for testing.
    func process(_ frame: FrameAccumulator.Frame, clientInfo: ClientInfo?) async throws -> ClientInfo? {
        switch frame.type {
        case .ping:
            // Echo the client's timestamp back in a PONG.
            try await server.send(data: frame.payload, type: .pong)
            return clientInfo

        case .pong:
            monitor.recordPong()
            return clientInfo

        case .authResponse:
            // Verify the client's proof; on success confirm with the server's proof.
            // On failure stay unauthenticated (the client times out); the gate counts
            // failures toward a lockout.
            if let confirm = await authGate.verifyResponse(frame.payload) {
                try await server.send(data: confirm, type: .authConfirm)
            }
            return clientInfo

        case .handshakeRequest:
            // LAN requires pairing first: ignore the handshake until authenticated.
            guard await authGate.isAuthenticated else {
                return clientInfo
            }
            switch handshakeHandler.handleHandshakeRequest(payload: frame.payload) {
            case .accepted(let response, let info):
                try await server.send(data: response, type: .handshakeResponse)
                return info
            case .rejected(let response, _):
                try await server.send(data: response, type: .handshakeResponse)
                return clientInfo
            }

        case .configRequest:
            guard let info = clientInfo else {
                // CONFIG_REQUEST before a successful handshake: ignore.
                return clientInfo
            }
            do {
                let (response, config) = try handshakeHandler.handleConfigRequest(
                    payload: frame.payload, clientInfo: info
                )
                try await server.send(data: response, type: .configResponse)
                // Begin streaming with the negotiated config (no-op by default), then
                // tell the client the stream is ready.
                try await onStreamStart(config)
                try await server.send(data: handshakeHandler.makeStartStreamMessage(), type: .startStream)
                // Streaming has begun — surface the negotiated client/config to the UI.
                await onClientConnected(info, config)
            } catch let error as ConnectionError {
                try await server.send(data: handshakeHandler.makeErrorMessage(error), type: .error)
            }
            return clientInfo

        default:
            return clientInfo
        }
    }

    static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
