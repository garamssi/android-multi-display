import Foundation
import Network
import Security

// S-H1: the connection and received-bytes AsyncStreams + continuations are created once in init and stored; rebuilding per access overwrites the continuation and drops subscribers.
public final class TCPServer: StreamServing, PacketReceiving, @unchecked Sendable {
    private var listener: NWListener?
    private var activeConnection: NWConnection?
    private let lock = NSLock()

    private var bonjourServiceType: String?

    private var bonjourOsVersion: String?

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

    public func advertiseBonjour(serviceType: String?, osVersion: String?) {
        lock.withLock {
            bonjourServiceType = serviceType
            bonjourOsVersion = osVersion
        }
    }

    public func start(port: UInt16, scope: ListenerScope) async throws {
        try lock.withLock {
            let tcpOptions = NWProtocolTCP.Options()
            tcpOptions.noDelay = true

            let params: NWParameters
            switch scope {
            case .loopback:
                params = NWParameters(tls: nil, tcp: tcpOptions)
                params.requiredInterfaceType = .loopback
            case .localNetwork:
                if let identity = TlsIdentity.loadSecIdentity() {
                    let tls = NWProtocolTLS.Options()
                    sec_protocol_options_set_local_identity(tls.securityProtocolOptions, identity)
                    params = NWParameters(tls: tls, tcp: tcpOptions)
                    Log.info(.server, "LAN listener: TLS enabled")
                } else {
                    params = NWParameters(tls: nil, tcp: tcpOptions)
                    Log.error(.server, "LAN TLS identity not found — run scripts/create_tls_cert.sh. Serving PLAINTEXT.")
                }
            }

            let nwPort = NWEndpoint.Port(rawValue: port)!
            let newListener = try NWListener(using: params, on: nwPort)

            // Bonjour advertisement requires NSBonjourServices + NSLocalNetworkUsageDescription in Info.plist.
            if let serviceType = bonjourServiceType {
                if let osVersion = bonjourOsVersion {
                    let txt = NWTXTRecord([ProtocolConstants.bonjourTxtKeyOS: osVersion])
                    newListener.service = NWListener.Service(type: serviceType, txtRecord: txt)
                } else {
                    newListener.service = NWListener.Service(type: serviceType)
                }
            }

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
        // Streams intentionally NOT finished here so the server can be restarted; they are finished only in deinit.
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
            activeConnection?.cancel()
            activeConnection = connection

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

    private func startReceiveLoop(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }

            if let data = data, !data.isEmpty {
                self.bytesContinuation.yield(data)
            }

            if isComplete || error != nil {
                return
            }

            let stillActive = self.lock.withLock { self.activeConnection === connection }
            if stillActive {
                self.startReceiveLoop(on: connection)
            }
        }
    }
}
