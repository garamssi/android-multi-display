import Foundation
import Network

/// TCP server for streaming data to (and receiving bytes from) an Android client.
/// The listener binds either loopback only (USB via `adb reverse`) or all interfaces
/// (additionally reachable over the local network), selected per `start(port:scope:)`.
///
/// The client-connection `AsyncStream` and the received-bytes `AsyncStream`, along
/// with their continuations, are created **once** in `init` and stored, so every
/// access returns the same live stream (S-H1). The previous computed-property
/// pattern rebuilt the stream on each access and overwrote the continuation,
/// dropping earlier subscribers.
///
/// The server is bidirectional: `send` writes framed packets, and a receive loop
/// yields raw inbound bytes via `receivedBytes` (see `PacketReceiving`) for the
/// control and input channels.
public final class TCPServer: StreamServing, PacketReceiving, @unchecked Sendable {
    private var listener: NWListener?
    private var activeConnection: NWConnection?
    private let lock = NSLock()

    private let connectionStream: AsyncStream<ClientConnection>
    private let connectionContinuation: AsyncStream<ClientConnection>.Continuation

    private let bytesStream: AsyncStream<Data>
    private let bytesContinuation: AsyncStream<Data>.Continuation

    public var clientConnections: AsyncStream<ClientConnection> {
        connectionStream
    }

    public var receivedBytes: AsyncStream<Data> {
        bytesStream
    }

    public init() {
        var connCont: AsyncStream<ClientConnection>.Continuation!
        connectionStream = AsyncStream(bufferingPolicy: .bufferingNewest(4)) { connCont = $0 }
        connectionContinuation = connCont

        var bytesCont: AsyncStream<Data>.Continuation!
        bytesStream = AsyncStream(bufferingPolicy: .bufferingNewest(256)) { bytesCont = $0 }
        bytesContinuation = bytesCont
    }

    deinit {
        lock.withLock {
            activeConnection?.cancel()
            listener?.cancel()
        }
        connectionContinuation.finish()
        bytesContinuation.finish()
    }

    public func start(port: UInt16, scope: ListenerScope) async throws {
        try lock.withLock {
            let params = NWParameters.tcp
            switch scope {
            case .loopback:
                // localhost only — reachable via the adb reverse tunnel (USB).
                params.requiredInterfaceType = .loopback
            case .localNetwork:
                // Bind all interfaces so the one listener serves both the adb-reverse
                // loopback path (USB) AND direct LAN clients. Leaving requiredInterfaceType
                // unset means "any interface". Plaintext/unauthenticated — opt-in only
                // (see docs/WIFI_TRANSPORT_DESIGN.md).
                break
            }

            // Set TCP_NODELAY
            if let tcpOptions = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
                tcpOptions.noDelay = true
            }

            let nwPort = NWEndpoint.Port(rawValue: port)!
            let newListener = try NWListener(using: params, on: nwPort)

            newListener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .failed:
                    self?.lock.withLock {
                        self?.listener = nil
                    }
                default:
                    break
                }
            }

            newListener.newConnectionHandler = { [weak self] connection in
                self?.handleNewConnection(connection)
            }

            newListener.start(queue: DispatchQueue(label: "com.desklink.server", qos: .userInteractive))
            self.listener = newListener
        }
    }

    public func stop() async {
        lock.withLock {
            activeConnection?.cancel()
            activeConnection = nil
            listener?.cancel()
            listener = nil
        }
        // Note: the streams are intentionally NOT finished here so the server can
        // be started again and continue delivering connections/bytes. They are
        // finished only in deinit.
    }

    public func send(data: Data, type: MessageType) async throws {
        let connection = lock.withLock { activeConnection }
        guard let connection = connection else {
            throw ConnectionError.lost
        }

        let packet = try PacketFramer.frame(type: type, payload: data)

        return try await withCheckedThrowingContinuation { continuation in
            connection.send(content: packet, completion: .contentProcessed { error in
                if error != nil {
                    continuation.resume(throwing: ConnectionError.lost)
                } else {
                    continuation.resume()
                }
            })
        }
    }

    // MARK: - Private

    private func handleNewConnection(_ connection: NWConnection) {
        lock.withLock {
            // Only allow one client at a time
            activeConnection?.cancel()
            activeConnection = connection

            // Configure connection
            connection.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.connectionContinuation.yield(ClientConnection())
                    self.startReceiveLoop(on: connection)
                case .failed, .cancelled:
                    self.lock.withLock {
                        if self.activeConnection === connection {
                            self.activeConnection = nil
                        }
                    }
                default:
                    break
                }
            }

            connection.start(queue: DispatchQueue(label: "com.desklink.connection", qos: .userInteractive))
        }
    }

    /// Continuously reads inbound bytes and yields them raw to `receivedBytes`.
    /// Framing/parsing is done downstream by `FrameAccumulator`.
    private func startReceiveLoop(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }

            if let data = data, !data.isEmpty {
                self.bytesContinuation.yield(data)
            }

            if isComplete || error != nil {
                // Connection closed or errored; stop reading.
                return
            }

            // Keep reading only while this is still the active connection.
            let stillActive = self.lock.withLock { self.activeConnection === connection }
            if stillActive {
                self.startReceiveLoop(on: connection)
            }
        }
    }
}
