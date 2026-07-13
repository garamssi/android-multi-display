import Foundation

/// Composition root that wires together the three DeskLink channels (S-L10):
/// - Control (7100): handshake + PING/PONG liveness.
/// - Video   (7101): capture → encode → stream (VIDEO_CONFIG then VIDEO_FRAME).
/// - Input   (7102): receive TOUCH_EVENT/TOUCH_BATCH → inject.
///
/// Each channel gets its own `TCPServer`. By default the listeners bind loopback and
/// ADB forwards all three ports (USB); when the user enables Wi-Fi, they bind all
/// interfaces so LAN clients can also connect (see `listenerScope`).
/// This object owns the transports and use cases and starts/stops them together.
@MainActor
public final class ServerCoordinator {
    // Transports (one per channel). Recreated on each start() so their AsyncStreams
    // are fresh: a Stop cancels the stream consumers, and an AsyncStream cannot be
    // re-consumed, so reusing them would leave the next connection hanging forever.
    private var controlServer = TCPServer()
    private var videoServer = TCPServer()
    private var inputServer = TCPServer()

    // Data-layer components.
    private let adbManager = ADBManager()
    private lazy var portForwardingWatcher = PortForwardingWatcher(adb: adbManager)
    private let displayManager = VirtualDisplayManager()
    private var screenCapturer: any ScreenCapturing = SCKScreenCapturer()
    private let encoder = HEVCEncoder()
    private let injector = CGEventInjector()

    /// Control-channel task(s). Kept separate from the pipeline tasks so a resolution
    /// change can rebuild the video/input pipeline without dropping the control channel.
    private var tasks: [Task<Void, Never>] = []

    /// Video + input pipeline tasks — cancelled and rebuilt on a resolution change.
    private var pipelineTasks: [Task<Void, Never>] = []

    /// The config the video pipeline is currently running at (nil = not booted).
    /// Lets a re-negotiation decide between a full reboot (resolution changed) and a
    /// cheap live bitrate change.
    private var currentStreamConfig: DisplayConfig?

    /// Interface scope the listeners bind on, resolved once from the user's Wi-Fi opt-in
    /// at `start()` and reused for any in-session rebind (bootStreaming re-creates the
    /// video/input servers). Loopback = USB only; localNetwork = USB + LAN.
    private var listenerScope: ListenerScope = .loopback

    /// Prevents overlapping boot/reboot sequences.
    private var isBooting = false

    // MARK: - Observability (UI wiring only — no effect on streaming behaviour)

    /// True between a successful `start()` and `stop()`. Guards late disconnect
    /// callbacks from a torn-down control channel so they can't resurrect status.
    private var isRunning = false

    /// Emitted on coarse lifecycle transitions: `.connecting` when the server
    /// begins listening, `.disconnected` when it stops.
    public var onStatusChange: ((ServerStatus) -> Void)?

    /// Emitted once a client completes handshake + config negotiation and streaming
    /// starts, carrying the negotiated device/output/frame metadata for the UI.
    public var onClientConnected: ((ClientInfo, DisplayConfig) -> Void)?

    /// Emitted when the active client drops while the server keeps listening.
    public var onClientDisconnected: (() -> Void)?

    public init() {}

    /// Sets up ADB forwarding and starts all three channels **listening** so the
    /// client can connect to any of them without a bind race. The video pipeline
    /// itself (virtual display + encoder + capture loop) is deferred until the
    /// control channel negotiates a config, at which point `bootStreaming` runs.
    ///
    /// The `config` argument is only a fallback default; the resolution/bitrate that
    /// actually drive the display and encoder come from the negotiated config passed
    /// to `bootStreaming` via `onStreamStart`.
    public func start(config: DisplayConfig) async throws {
        // Fresh transports/capturer each start (see property comment). Without this,
        // a Stop → Start cycle reuses already-consumed AsyncStreams and the next
        // client's handshake is never read → the tablet hangs on "connecting".
        controlServer = TCPServer()
        videoServer = TCPServer()
        inputServer = TCPServer()
        screenCapturer = SCKScreenCapturer()
        for task in pipelineTasks { task.cancel() }
        pipelineTasks.removeAll()
        currentStreamConfig = nil

        // Resolve the listener scope once for this session from the user's opt-in. USB
        // (loopback) is the default; LAN binding is added only when Wi-Fi is enabled.
        listenerScope = TransportSettings.wifiEnabled ? .localNetwork : .loopback
        Log.info(.server, "starting servers scope=\(listenerScope == .localNetwork ? "loopback+LAN" : "loopback")")

        // Observability: server is now coming up and listening for a client.
        onStatusChange?(.connecting)

        // Request Accessibility up front so injected touches actually take effect and
        // the app is listed in System Settings > Privacy & Security > Accessibility.
        // Prompts only when not yet trusted; no-op once granted.
        _ = CGEventInjector.requestAccessibility()

        // USB(ADB): keep the reverse tunnel reconciled against device presence for the
        // whole session. A one-shot setup here would silently fail (and never retry)
        // if the device isn't ready yet, or would be lost on a re-plug — both of which
        // leave the tablet unable to reach the Mac after "waiting for device".
        await portForwardingWatcher.start()

        // Start ALL THREE servers listening up front so that, the moment the control
        // channel sends START_STREAM, the client can immediately connect to the video
        // (7101) and input (7102) sockets — no bind race. The video/input pipelines
        // are wired later in bootStreaming, once the negotiated config is known.
        //
        // Failure-atomic: the watcher and any already-bound listener are started before
        // a bind can throw (e.g. a port still held from a prior run). If any bind fails,
        // roll everything back before rethrowing — otherwise the watcher's 1s poll loop
        // and a bound socket would linger with no teardown path (the caller only flips
        // UI state on catch, it does not know to stop us).
        // In LAN mode, advertise the control channel over Bonjour so a tablet can find
        // the Mac without a typed IP. USB (loopback) never advertises. The TXT record
        // carries the OS version shown on the tablet's server card.
        if listenerScope == .localNetwork {
            let version = ProcessInfo.processInfo.operatingSystemVersion
            let osVersion = "macOS \(version.majorVersion).\(version.minorVersion)"
            controlServer.advertiseBonjour(
                serviceType: ProtocolConstants.bonjourServiceType,
                osVersion: osVersion
            )
        }

        do {
            try await controlServer.start(port: ProtocolConstants.portControl, scope: listenerScope)
            try await videoServer.start(port: ProtocolConstants.portVideo, scope: listenerScope)
            try await inputServer.start(port: ProtocolConstants.portInput, scope: listenerScope)
        } catch {
            await portForwardingWatcher.stop()
            await controlServer.stop()
            await videoServer.stop()
            await inputServer.stop()
            // Match stop()'s teardown: if the watcher already applied `adb reverse`
            // before the bind failed, remove it so a failed start leaves nothing behind.
            try? await adbManager.removePortForwarding()
            isRunning = false
            onStatusChange?(.disconnected)
            throw error
        }

        // All three sockets are bound and listening — the server is officially up.
        isRunning = true

        // Control channel (7100): handshake + config negotiation + PING/PONG.
        // On successful negotiation, ControlChannelUseCase invokes onStreamStart with
        // the negotiated config BEFORE sending START_STREAM, so the virtual display
        // and encoder exist by the time the client connects to the video channel.
        //
        // The onClientConnected/onClientDisconnected hooks are pure observability:
        // they surface the negotiated ClientInfo + DisplayConfig (device model,
        // resolution, fps, codec) to the UI without altering the streaming flow.
        // LAN requires PIN pairing; the key is derived from the CURRENT displayed PIN at
        // each connection, so a rotated PIN is honored without restarting the server. USB
        // (loopback) returns nil and skips auth.
        let isLan = listenerScope == .localNetwork
        let control = ControlChannelUseCase(
            server: controlServer,
            receiver: controlServer,
            authKeyProvider: { isLan ? PairingCrypto.derivePSK(pin: PairingPin.current) : nil },
            onStreamStart: { [weak self] negotiated in
                try await self?.bootStreaming(config: negotiated)
            },
            onClientConnected: { [weak self] info, negotiated in
                await self?.handleClientConnected(info: info, config: negotiated)
            },
            onClientDisconnected: { [weak self] in
                await self?.handleClientDisconnected()
            }
        )
        tasks.append(Task { try? await control.run() })
    }

    // MARK: - Observability hooks (MainActor; UI-only)

    private func handleClientConnected(info: ClientInfo, config: DisplayConfig) {
        guard isRunning else { return }
        Log.info(.server, "client connected: \(info.deviceModel) \(config.width)x\(config.height) @\(config.fps) codec=\(config.codec)")
        onClientConnected?(info, config)
    }

    private func handleClientDisconnected() {
        guard isRunning else { return }
        Log.info(.server, "client disconnected (server still listening)")
        onClientDisconnected?()
    }

    /// Creates and starts the video/input pipeline for the negotiated `config`.
    /// Called from the control channel's `onStreamStart` hook before START_STREAM is
    /// sent, so the display and encoder are ready before the client connects to the
    /// (already-listening) video channel.
    ///
    /// Idempotent: only the first call per `start()` cycle takes effect; later
    /// negotiations are ignored so we never double-create the display/encoder or
    /// launch duplicate streaming loops.
    private func bootStreaming(config: DisplayConfig) async throws {
        guard !isBooting else { return }
        isBooting = true
        defer { isBooting = false }

        let previous = currentStreamConfig
        let isReconnect = previous != nil
        let resolutionChanged =
            previous.map { $0.width != config.width || $0.height != config.height } ?? true
        Log.info(.stream, "boot: config \(config.width)x\(config.height) bitrate=\(config.bitrateKbps) reconnect=\(isReconnect) resChanged=\(resolutionChanged)")

        // On a reconnect, rebuild the video/input pipeline from scratch. The video
        // and input TCP servers must be RE-created (not reused): their AsyncStreams
        // can only be consumed once, so a new streaming/input task cannot read from
        // the old servers' streams → the client would connect but never receive
        // VIDEO_CONFIG (black screen). A fresh SCKScreenCapturer also avoids a
        // capture restart race. The control server (7100) is left untouched.
        if isReconnect {
            for task in pipelineTasks { task.cancel() }
            pipelineTasks.removeAll()
            await screenCapturer.stopCapture()
            await videoServer.stop()
            await inputServer.stop()
            if resolutionChanged {
                await displayManager.destroyDisplay()
            }
            // Settle: let the OS release ports 7101/7102 and the private virtual
            // display API quiesce before rebinding / recreating.
            try? await Task.sleep(nanoseconds: 300_000_000)

            videoServer = TCPServer()
            inputServer = TCPServer()
            screenCapturer = SCKScreenCapturer()
            try await videoServer.start(port: ProtocolConstants.portVideo, scope: listenerScope)
            try await inputServer.start(port: ProtocolConstants.portInput, scope: listenerScope)
        }

        // Bring up the virtual display at the CLIENT'S negotiated resolution (first
        // boot, or when the resolution changed) so the encoder captures at native
        // size (no tablet-side upscaling → no blur).
        if previous == nil || resolutionChanged {
            try await displayManager.createDisplay(config: config)
        }
        let displayID = displayManager.displayID

        // Configure the encoder so VIDEO_CONFIG / frames are produced at the
        // negotiated resolution and bitrate.
        try await encoder.configure(config: config)
        currentStreamConfig = config

        // Input channel (7102): receive touch → inject.
        let receiveInput = ReceiveInputUseCase(receiver: inputServer, injector: injector, displayID: displayID)
        pipelineTasks.append(Task { try? await receiveInput.run() })

        // Video channel (7101): capture → encode → stream.
        let streaming = StartStreamingUseCase(
            displayManager: displayManager,
            screenCapturer: screenCapturer,
            encoder: encoder,
            streamServer: videoServer
        )
        pipelineTasks.append(Task { try? await streaming.execute(config: config, displayID: displayID) })
    }

    /// Stops all channels and tears down the virtual display and ADB forwarding.
    public func stop() async {
        // Flip first so any in-flight disconnect callback from the tearing-down
        // control channel is suppressed (see handleClientDisconnected).
        isRunning = false
        onStatusChange?(.disconnected)

        for task in tasks { task.cancel() }
        tasks.removeAll()
        for task in pipelineTasks { task.cancel() }
        pipelineTasks.removeAll()
        currentStreamConfig = nil

        await inputServer.stop()
        await controlServer.stop()
        await videoServer.stop()
        await injector.stopReceiving()
        await screenCapturer.stopCapture()
        await displayManager.destroyDisplay()
        await portForwardingWatcher.stop()
        try? await adbManager.removePortForwarding()
    }
}
