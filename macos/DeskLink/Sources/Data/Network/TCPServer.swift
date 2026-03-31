import Foundation
import Network

/// TCP server for streaming data to Android client.
public final class TCPServer: StreamServing, @unchecked Sendable {
    private var listener: NWListener?
    private var activeConnection: NWConnection?
    private let lock = NSLock()
    private var connectionContinuation: AsyncStream<ClientConnection>.Continuation?

    public var clientConnections: AsyncStream<ClientConnection> {
        AsyncStream { continuation in
            lock.withLock {
                self.connectionContinuation = continuation
            }
        }
    }

    public init() {}

    deinit {
        lock.withLock {
            activeConnection?.cancel()
            listener?.cancel()
        }
    }

    public func start(port: UInt16) async throws {
        try lock.withLock {
            let params = NWParameters.tcp
            params.requiredInterfaceType = .loopback // localhost only (ADB forwarding)

            // Set TCP_NODELAY
            if let tcpOptions = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
                tcpOptions.noDelay = true
            }

            let nwPort = NWEndpoint.Port(rawValue: port)!
            let newListener = try NWListener(using: params, on: nwPort)

            newListener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .failed(let error):
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
            connectionContinuation?.finish()
            connectionContinuation = nil
        }
    }

    public func send(data: Data, type: MessageType) async throws {
        let connection = lock.withLock { activeConnection }
        guard let connection = connection else {
            throw ConnectionError.lost
        }

        let packet = PacketFramer.frame(type: type, payload: data)

        return try await withCheckedThrowingContinuation { continuation in
            connection.send(content: packet, completion: .contentProcessed { error in
                if let error = error {
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
                switch state {
                case .ready:
                    let client = ClientConnection()
                    self?.lock.withLock {
                        self?.connectionContinuation?.yield(client)
                    }
                case .failed, .cancelled:
                    self?.lock.withLock {
                        if self?.activeConnection === connection {
                            self?.activeConnection = nil
                        }
                    }
                default:
                    break
                }
            }

            connection.start(queue: DispatchQueue(label: "com.desklink.connection", qos: .userInteractive))
        }
    }
}
