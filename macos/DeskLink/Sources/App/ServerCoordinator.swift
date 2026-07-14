import Foundation

@MainActor
public final class ServerCoordinator {
    // Recreated each start(): a consumed AsyncStream cannot be re-consumed, so reusing a stack would hang the next connection.
    private var usbStack: ChannelStack?
    private var lanStack: ChannelStack?

    private let adbManager = ADBManager()
    private lazy var portForwardingWatcher = PortForwardingWatcher(adb: adbManager)
    private let displayManager = VirtualDisplayManager()
    private var screenCapturer: any ScreenCapturing = SCKScreenCapturer()
    private let encoder = HEVCEncoder()
    private let injector = CGEventInjector()

    private var tasks: [Task<Void, Never>] = []

    private var pipelineTasks: [Task<Void, Never>] = []

    private var currentStreamConfig: DisplayConfig?

    private var isBooting = false

    // MARK: - Connection state (single source of truth)

    private var connection: ConnectionSnapshot = .stopped {
        didSet { onConnectionChanged?(connection) }
    }

    public var onConnectionChanged: ((ConnectionSnapshot) -> Void)?

    private var connectedTransports: Set<TransportKind> = []

    private var activeSession: (info: ClientInfo, config: DisplayConfig, kind: TransportKind)?

    private var streamGeneration = 0

    private var isRunning: Bool {
        if case .stopped = connection { return false }
        return true
    }

    public init() {}

    private func setConnection(_ new: ConnectionSnapshot) {
        connection = new
    }

    public func start(config: DisplayConfig) async throws {
        // Fresh stacks/capturer each start: a consumed AsyncStream cannot be re-consumed, so reuse would hang the next client's handshake.
        let usb = ChannelStack(
            kind: .usb,
            scope: .loopback,
            controlPort: ProtocolConstants.portControl,
            videoPort: ProtocolConstants.portVideo,
            inputPort: ProtocolConstants.portInput,
            requiresPairing: false
        )
        let lan: ChannelStack? = TransportSettings.wifiEnabled
            ? ChannelStack(
                kind: .lan,
                scope: .localNetwork,
                controlPort: ProtocolConstants.portControlLan,
                videoPort: ProtocolConstants.portVideoLan,
                inputPort: ProtocolConstants.portInputLan,
                requiresPairing: true
            )
            : nil
        usbStack = usb
        lanStack = lan
        screenCapturer = SCKScreenCapturer()
        for task in pipelineTasks { task.cancel() }
        pipelineTasks.removeAll()
        currentStreamConfig = nil
        connectedTransports.removeAll()
        activeSession = nil

        Log.info(.server, "starting servers: USB(loopback 7100-7102)\(lan != nil ? " + LAN(TLS+PIN 7110-7112)" : "")")

        _ = CGEventInjector.requestAccessibility()

        await portForwardingWatcher.start()

        if let lan {
            let version = ProcessInfo.processInfo.operatingSystemVersion
            let osVersion = "macOS \(version.majorVersion).\(version.minorVersion)"
            lan.controlServer.advertiseBonjour(
                serviceType: ProtocolConstants.bonjourServiceType,
                osVersion: osVersion
            )
        }

        do {
            try await usb.startListening()
            try await lan?.startListening()
        } catch {
            await portForwardingWatcher.stop()
            await usb.stop()
            await lan?.stop()
            try? await adbManager.removePortForwarding()
            usbStack = nil
            lanStack = nil
            setConnection(.stopped)
            throw error
        }

        setConnection(.waiting)

        startControlChannel(for: usb)
        if let lan { startControlChannel(for: lan) }
    }

    private func startControlChannel(for stack: ChannelStack) {
        let kind = stack.kind
        let requiresPairing = stack.requiresPairing
        let control = ControlChannelUseCase(
            server: stack.controlServer,
            receiver: stack.controlServer,
            authKeyProvider: { requiresPairing ? PairingCrypto.derivePSK(pin: PairingPin.current) : nil },
            onStreamStart: { [weak self] negotiated in
                try await self?.bootStreaming(config: negotiated, transport: kind)
            },
            onClientConnected: { [weak self] info, negotiated in
                await self?.handleClientConnected(transport: kind, info: info, config: negotiated)
            },
            onClientDisconnected: {
                // Keep-alive is intentionally decoupled from UI state: it false-expires during the connect burst, so it must not end the session (video liveness does).
                Log.info(.server, "control keep-alive lapsed on \(kind) (UI unaffected; video liveness drives state)")
            }
        )
        tasks.append(Task { try? await control.run() })
    }

    // MARK: - Connection-state transitions (MainActor)

    private func handleClientConnected(transport kind: TransportKind, info: ClientInfo, config: DisplayConfig) {
        guard isRunning else { return }
        activeSession = (info, config, kind)
        connectedTransports.insert(kind)
        Log.info(.server, "client connected on \(kind): \(info.deviceModel) \(config.width)x\(config.height) @\(config.fps) codec=\(config.codec); connected=\(connectedTransports)")
        setConnection(.connected(info, config, kind))
    }

    private func handleClientPresent(transport kind: TransportKind, generation: Int) {
        guard generation == streamGeneration, isRunning, let session = activeSession else { return }
        connectedTransports.insert(kind)
        setConnection(.connected(session.info, session.config, session.kind))
    }

    private func handleStreamEnded(transport kind: TransportKind, generation: Int) {
        guard generation == streamGeneration else { return }
        guard isRunning else { return }
        guard connectedTransports.remove(kind) != nil else { return }
        Log.info(.server, "video session ended on \(kind); remaining=\(connectedTransports)")
        guard connectedTransports.isEmpty else { return }
        setConnection(.waiting)
    }

    private func bootStreaming(config: DisplayConfig, transport kind: TransportKind) async throws {
        guard !isBooting else { return }
        isBooting = true
        defer { isBooting = false }

        guard let stack = (kind == .usb ? usbStack : lanStack) else { return }

        // Bump generation FIRST (before teardown): teardown fires a stale onClientGone that must be filtered, else it flips a live session back to .waiting.
        streamGeneration += 1
        let generation = streamGeneration

        let previous = currentStreamConfig
        let isReconnect = previous != nil
        let resolutionChanged =
            previous.map { $0.width != config.width || $0.height != config.height } ?? true
        Log.info(.stream, "boot: config \(config.width)x\(config.height) bitrate=\(config.bitrateKbps) reconnect=\(isReconnect) resChanged=\(resolutionChanged)")

        // Reconnect must RE-create the video/input servers (not reuse): an AsyncStream can be consumed once, else the client connects but never gets VIDEO_CONFIG (black screen).
        if isReconnect {
            for task in pipelineTasks { task.cancel() }
            pipelineTasks.removeAll()
            await screenCapturer.stopCapture()
            await stack.videoServer.stop()
            await stack.inputServer.stop()
            if resolutionChanged {
                await displayManager.destroyDisplay()
            }
            // Let the OS release the ports and the private virtual-display API quiesce before rebinding.
            try? await Task.sleep(nanoseconds: 300_000_000)

            stack.videoServer = TCPServer()
            stack.inputServer = TCPServer()
            screenCapturer = SCKScreenCapturer()
            try await stack.videoServer.start(port: stack.videoPort, scope: stack.scope)
            try await stack.inputServer.start(port: stack.inputPort, scope: stack.scope)
        }

        if previous == nil || resolutionChanged {
            try await displayManager.createDisplay(config: config)
        }
        let displayID = displayManager.displayID

        if let actual = displayManager.activeResolution {
            let matches = actual.width == config.width && actual.height == config.height
            Log.info(.stream, "virtual display active \(actual.width)x\(actual.height) vs requested \(config.width)x\(config.height)\(matches ? "" : " (MISMATCH: private-API mode fallback)")")
        }

        try await encoder.configure(config: config)
        currentStreamConfig = config

        let receiveInput = ReceiveInputUseCase(receiver: stack.inputServer, injector: injector, displayID: displayID)
        pipelineTasks.append(Task { try? await receiveInput.run() })

        let streaming = StartStreamingUseCase(
            displayManager: displayManager,
            screenCapturer: screenCapturer,
            encoder: encoder,
            streamServer: stack.videoServer,
            onClientPresent: { [weak self] in
                await self?.handleClientPresent(transport: kind, generation: generation)
            },
            onClientGone: { [weak self] in
                await self?.handleStreamEnded(transport: kind, generation: generation)
            }
        )
        pipelineTasks.append(Task { try? await streaming.execute(config: config, displayID: displayID) })
    }

    public func stop() async {
        // Flip to .stopped first so isRunning is false and in-flight teardown callbacks are suppressed.
        setConnection(.stopped)

        for task in tasks { task.cancel() }
        tasks.removeAll()
        for task in pipelineTasks { task.cancel() }
        pipelineTasks.removeAll()
        currentStreamConfig = nil
        connectedTransports.removeAll()
        activeSession = nil

        await usbStack?.stop()
        await lanStack?.stop()
        usbStack = nil
        lanStack = nil
        await injector.stopReceiving()
        await screenCapturer.stopCapture()
        await displayManager.destroyDisplay()
        await portForwardingWatcher.stop()
        try? await adbManager.removePortForwarding()
    }
}
