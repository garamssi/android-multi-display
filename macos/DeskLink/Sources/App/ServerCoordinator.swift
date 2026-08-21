import CoreGraphics
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

    private let audioPreference = AudioOutputPreference()

    private let displayModePreference = DisplayModePreference()

    // Chosen at start() from the preference; bootStreaming only asks it to prepare.
    private var streamSource: any StreamSourceProviding = VirtualDisplaySource(
        displayManager: VirtualDisplayManager()
    )

    private var currentStreamSource: StreamSource?

    // The mode this session was started in, announced to the client in the handshake.
    private var activeMode: DisplayMode = DisplayModePreference.defaultMode

    private static func makeStreamSource(
        for mode: DisplayMode,
        displayManager: any VirtualDisplayManaging
    ) -> any StreamSourceProviding {
        switch mode {
        case .extend:
            return VirtualDisplaySource(displayManager: displayManager)
        case .mirror:
            // The Mac's own main screen. Which display to mirror is a Mac-side choice, and
            // the main one is the only sensible default.
            return MirrorDisplaySource(displayID: CGMainDisplayID())
        }
    }

    // Held so stop() can release the tap synchronously: cancelling the streaming task also
    // releases it, but stop() does not await cancelled tasks, and while a tap is held the
    // Mac's speakers are silent.
    private var audioCapturer: (any AudioCapturing)?

    private var tasks: [Task<Void, Never>] = []

    private var pipelineTasks: [Task<Void, Never>] = []

    private var currentStreamConfig: DisplayConfig?

    // Replayed when a display-mode change restarts the session; the streaming resolution is
    // renegotiated with the client afterwards, so this is only the starting point.
    private var lastStartConfig: DisplayConfig?

    private var restartGate = DisplayModeRestartGate()

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

    public init() {
        observeDisplayModePreference()
    }

    private func setConnection(_ new: ConnectionSnapshot) {
        connection = new
    }

    public func start(config: DisplayConfig) async throws {
        // The mode is read once per session: switching it changes the Mac's own display
        // arrangement, so it takes effect on the next start rather than mid-stream.
        lastStartConfig = config
        let mode = displayModePreference.mode
        streamSource = Self.makeStreamSource(for: mode, displayManager: displayManager)
        currentStreamSource = nil
        activeMode = mode
        // Mirror never accepts input, so its input port is not bound at all.
        let inputPort: UInt16? = mode.acceptsInput ? ProtocolConstants.portInput : nil
        let inputPortLan: UInt16? = mode.acceptsInput ? ProtocolConstants.portInputLan : nil
        Log.info(.server, "display mode: \(mode.wireValue)\(mode.acceptsInput ? "" : " (input disabled)")")

        // Fresh stacks/capturer each start: a consumed AsyncStream cannot be re-consumed, so reuse would hang the next client's handshake.
        let usb = ChannelStack(
            kind: .usb,
            scope: .loopback,
            controlPort: ProtocolConstants.portControl,
            videoPort: ProtocolConstants.portVideo,
            inputPort: inputPort,
            audioPort: ProtocolConstants.portAudio,
            requiresPairing: false
        )
        let lan: ChannelStack? = TransportSettings.wifiEnabled
            ? ChannelStack(
                kind: .lan,
                scope: .localNetwork,
                controlPort: ProtocolConstants.portControlLan,
                videoPort: ProtocolConstants.portVideoLan,
                inputPort: inputPortLan,
                // Audio is USB-only; see startAudioStreamingIfEnabled.
                audioPort: nil,
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

        Log.info(.server, "starting servers: USB(loopback 7100-7103)\(lan != nil ? " + LAN(TLS+PIN 7110-7113)" : "")")

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

        // USB only: audio is uncompressed PCM (~1.5 Mbps) and there is exactly one system
        // tap, so a second stack would either contend for it or push raw PCM over Wi-Fi.
        // The LAN audio port stays reserved for when a codec exists (see the roadmap).
        startAudioStreamingIfEnabled(for: usb)
        observeAudioPreference(for: usb)
    }

    // The tap only starts once a client connects (see StreamAudioUseCase), so running this
    // loop costs nothing and does not mute the Mac on its own.
    private func startAudioStreamingIfEnabled(for stack: ChannelStack) {
        guard audioPreference.routeToTablet else {
            Log.info(.stream, "audio: routing to tablet is off; audio stays on the Mac")
            return
        }
        guard #available(macOS 14.2, *) else {
            // Process taps are the only way to capture system audio without leaving it
            // playing on the Mac, and they do not exist before 14.2.
            Log.error(.stream, "audio: needs macOS 14.2 or later; audio stays on the Mac")
            return
        }
        let capturer = CoreAudioTapCapturer()
        audioCapturer = capturer
        let useCase = StreamAudioUseCase(capturer: capturer, streamServer: stack.audioServer)
        tasks.append(Task { try? await useCase.execute() })
    }

    // Follows the preference while the server runs. Reading it only at start meant that
    // turning routing off mid-session did nothing until Stop/Start — on the side that owns
    // the mute, so the user could not get their Mac's sound back without restarting.
    private func observeAudioPreference(for stack: ChannelStack) {
        tasks.append(Task { [weak self] in
            for await enabled in AudioOutputPreference.routeToTabletChanges() {
                guard let self else { return }
                await self.applyAudioPreference(enabled, for: stack)
            }
        })
    }

    // Not in `tasks`: those are cancelled by stop(), and this loop has to survive the stop
    // that its own restart performs. It costs one notification observer and does nothing
    // while the server is stopped.
    private func observeDisplayModePreference() {
        Task { [weak self] in
            for await mode in DisplayModePreference.modeChanges() {
                guard let self else { return }
                await self.handleDisplayModeChange(mode)
            }
        }
    }

    // The mode decides what is captured and at what size, and the client learns both only
    // from the handshake, so the session has to be rebuilt. Doing it here rather than asking
    // the user to press Stop then Start is what keeps the tablet: the client gives up
    // reconnecting after a few seconds, so a hand-timed restart drops it.
    private func handleDisplayModeChange(_ mode: DisplayMode) async {
        guard isRunning else { return }
        guard var next = restartGate.request(mode, current: activeMode) else { return }
        while true {
            await restartSession(into: next)
            guard let queued = restartGate.finish() else { return }
            next = queued
        }
    }

    private func restartSession(into mode: DisplayMode) async {
        guard let config = lastStartConfig else { return }
        Log.info(.server, "display mode changed to \(mode.wireValue); restarting session")
        await endSession()
        do {
            try await start(config: config)
        } catch {
            // Surfaced, not swallowed: the UI follows `connection`, which stop() already set
            // to .stopped, so the user sees a stopped server and can start it again.
            Log.error(.server, "restart after display mode change failed: \(error)")
        }
    }

    private func applyAudioPreference(_ enabled: Bool, for stack: ChannelStack) async {
        guard isRunning else { return }
        if enabled {
            guard audioCapturer == nil else { return }
            startAudioStreamingIfEnabled(for: stack)
        } else {
            guard let capturer = audioCapturer else { return }
            audioCapturer = nil
            // Releasing the tap is what hands the speakers back.
            await capturer.stopCapture()
            Log.info(.stream, "audio: routing turned off; audio returns to the Mac")
        }
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
            },
            displayMode: activeMode
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
            if let inputPort = stack.inputPort {
                try await stack.inputServer.start(port: inputPort, scope: stack.scope)
            }
        }

        // Only extend needs a display created, and only when the resolution changed; the
        // provider decides, so this path no longer knows which mode is running.
        let source: StreamSource
        if previous == nil || resolutionChanged {
            source = try await streamSource.prepare(config: config)
            currentStreamSource = source
        } else if let existing = currentStreamSource {
            source = existing
        } else {
            source = try await streamSource.prepare(config: config)
            currentStreamSource = source
        }

        if source.captureWidth != config.width || source.captureHeight != config.height {
            Log.info(
                .stream,
                "capture size \(source.captureWidth)x\(source.captureHeight) differs from requested "
                    + "\(config.width)x\(config.height) (mirror uses the display's own size; a virtual "
                    + "display may have fallen back to another mode)"
            )
        }

        // Encode at the size actually being captured. Configuring the encoder from the
        // request instead would upscale a smaller picture into a larger frame and spend
        // the whole bitrate on pixels that carry no detail.
        let captureConfig = config.withResolution(width: source.captureWidth, height: source.captureHeight)
        try await encoder.configure(config: captureConfig)
        currentStreamConfig = config

        if source.acceptsInput {
            let receiveInput = ReceiveInputUseCase(
                receiver: stack.inputServer,
                injector: injector,
                displayID: source.displayID
            )
            pipelineTasks.append(Task { try? await receiveInput.run() })
        } else {
            Log.info(.stream, "mirror: input disabled; touches would move the cursor on the Mac's own screen")
        }

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
            },
            onSharingEndedByUser: { [weak self] in
                // Tear the whole session down so audio stops with the picture and the menu
                // returns to Start Server, instead of a running server nobody can restart.
                await self?.stop()
            }
        )
        pipelineTasks.append(Task { try? await streaming.execute(config: captureConfig, source: source) })
    }

    public func stop() async {
        await endSession()
        // Only a full stop releases the device mappings. A mode-change restart keeps them:
        // re-adding four `adb reverse` mappings takes four process spawns, and until they
        // are back the client's reconnect attempts are refused outright.
        await portForwardingWatcher.stop()
        try? await adbManager.removePortForwarding()
    }

    private func endSession() async {
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
        // Release the tap explicitly: TCPServer.stop() does not finish its connection
        // stream (so it can restart), so the streaming loop ends by cancellation, which
        // stop() does not await. This guarantees the speakers are back before it returns.
        await audioCapturer?.stopCapture()
        audioCapturer = nil
        // Only extend has anything to release; mirror's teardown is a no-op.
        await streamSource.teardown()
        currentStreamSource = nil
        await injector.stopReceiving()
        await screenCapturer.stopCapture()
        await displayManager.destroyDisplay()
    }
}
