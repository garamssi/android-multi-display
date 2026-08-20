import Foundation

public final class ControlChannelUseCase: Sendable {
    private let server: any StreamServing
    private let receiver: any PacketReceiving
    private let monitor: ConnectionMonitor
    private let handshakeHandler = HandshakeHandler()

    private let authGate: AuthGate

    private let onStreamStart: @Sendable (DisplayConfig) async throws -> Void

    private let onClientConnected: @Sendable (ClientInfo, DisplayConfig) async -> Void

    private let onClientDisconnected: @Sendable () async -> Void

    public init(
        server: any StreamServing,
        receiver: any PacketReceiving,
        monitor: ConnectionMonitor = ConnectionMonitor(),
        authKeyProvider: @escaping @Sendable () -> Data? = { nil },
        onStreamStart: @escaping @Sendable (DisplayConfig) async throws -> Void = { _ in },
        onClientConnected: @escaping @Sendable (ClientInfo, DisplayConfig) async -> Void = { _, _ in },
        onClientDisconnected: @escaping @Sendable () async -> Void = { }
    ) {
        self.server = server
        self.receiver = receiver
        self.monitor = monitor
        self.authGate = AuthGate(keyProvider: authKeyProvider)
        self.onStreamStart = onStreamStart
        self.onClientConnected = onClientConnected
        self.onClientDisconnected = onClientDisconnected
    }

    public func run() async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask { [self] in try await runReceiveLoop() }
            group.addTask { [self] in try await runKeepAlive() }

            try await group.next()
            group.cancelAll()
        }
    }

    // On PONG-timeout runPingLoop returns and we loop to await the next client, so reconnects are handled WITHOUT restarting the server; the receive loop keeps running on the same byte stream.
    private func runKeepAlive() async throws {
        for await _ in server.clientConnections {
            if let challenge = await authGate.beginChallenge() {
                try? await server.send(data: challenge, type: .authChallenge)
            }
            monitor.recordPong()
            try? await runPingLoop() // returns on PONG-timeout; then await next client
            if Task.isCancelled { break }
            await onClientDisconnected()
        }
    }

    private func runPingLoop() async throws {
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

    private func runReceiveLoop() async throws {
        var accumulator = FrameAccumulator()
        var clientInfo: ClientInfo?

        for await chunk in receiver.receivedBytes {
            monitor.recordPong()
            do {
                let frames = try accumulator.append(chunk)
                for frame in frames {
                    clientInfo = try await process(frame, clientInfo: clientInfo)
                }
            } catch is CancellationError {
                throw CancellationError() // let a real cancellation tear the loop down
            } catch {
                // Resync (fresh accumulator, drop negotiation state) instead of tearing down: a prior client may have dropped mid-frame and corrupted the next client's framing.
                accumulator = FrameAccumulator()
                clientInfo = nil
            }
        }
        throw ConnectionError.lost
    }

    func process(_ frame: FrameAccumulator.Frame, clientInfo: ClientInfo?) async throws -> ClientInfo? {
        switch frame.type {
        case .ping:
            try await server.send(data: frame.payload, type: .pong)
            return clientInfo

        case .pong:
            monitor.recordPong()
            return clientInfo

        case .authResponse:
            if let confirm = await authGate.verifyResponse(frame.payload) {
                try await server.send(data: confirm, type: .authConfirm)
            }
            return clientInfo

        case .handshakeRequest:
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
                return clientInfo
            }
            do {
                let (response, config) = try handshakeHandler.handleConfigRequest(
                    payload: frame.payload, clientInfo: info
                )
                try await server.send(data: response, type: .configResponse)
                try await onStreamStart(config)
                try await server.send(data: handshakeHandler.makeStartStreamMessage(), type: .startStream)
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
