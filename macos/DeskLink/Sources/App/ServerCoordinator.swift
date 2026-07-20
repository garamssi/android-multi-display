import Foundation

/// Composition root that wires together the three DeskLink channels (S-L10):
/// - Control: handshake + PING/PONG liveness.
/// - Video:   capture → encode → stream (VIDEO_CONFIG then VIDEO_FRAME).
/// - Input:   receive TOUCH_EVENT/TOUCH_BATCH → inject.
///
/// The channels are grouped into `ChannelStack`s, one per transport. The USB stack
/// (loopback, plaintext, no PIN, 7100-7102) is always running; the LAN stack (all
/// interfaces, TLS, PIN pairing, 7110-7112) runs alongside it when the user enables
/// Wi-Fi. Both listen at once, so a USB client connects PIN-free even while Wi-Fi is up
/// (the fix for USB breaking whenever Wi-Fi was enabled). Only one client streams at a
/// time — a single shared video pipeline is bound to whichever stack negotiates first.
/// This object owns the stacks and use cases and starts/stops them together.
@MainActor
public final class ServerCoordinator {
    // The two channel stacks. Recreated on each start() so their AsyncStreams are fresh:
    // a Stop cancels the stream consumers, and an AsyncStream cannot be re-consumed, so
    // reusing them would leave the next connection hanging forever. `lanStack` is nil
    // when Wi-Fi serving is disabled.
    private var usbStack: ChannelStack?
    private var lanStack: ChannelStack?

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

    /// Prevents overlapping boot/reboot sequences.
    private var isBooting = false

    // MARK: - Connection state (single source of truth)

    /// The one place the connection state lives. Every UI-visible transition goes through
    /// `setConnection`, which emits `onConnectionChanged`. Nothing else writes UI state.
    private var connection: ConnectionSnapshot = .stopped {
        didSet { onConnectionChanged?(connection) }
    }

    /// Observed by the UI (ServerViewModel). Fired on every connection-state transition.
    public var onConnectionChanged: ((ConnectionSnapshot) -> Void)?

    /// Transports whose client completed negotiation and has a live video session. The
    /// state is the AGGREGATE across both stacks: any connected transport ⇒ `.connected`;
    /// it returns to `.waiting` only once this is empty. Used so a probe/idle connection
    /// on the other stack's listener can't clobber a live client.
    private var connectedTransports: Set<TransportKind> = []

    /// The most recently negotiated client session (device info + config + transport).
    /// Kept so a video-only reconnect (tablet returning from the background, no fresh
    /// handshake) can re-enter `.connected` with the same metadata. Cleared on stop().
    private var activeSession: (info: ClientInfo, config: DisplayConfig, kind: TransportKind)?

    /// Monotonic id for the active video pipeline. Each `bootStreaming` bumps it and hands
    /// its value to that pipeline's "client gone" callback, so a stale callback from a
    /// pipeline that was already torn down by a reconnect/rebuild is ignored.
    private var streamGeneration = 0

    /// True while the server is running (listening). Derived from the single state so
    /// there is no separate flag to fall out of sync.
    private var isRunning: Bool {
        if case .stopped = connection { return false }
        return true
    }

    public init() {}

    /// Applies a connection-state transition (single writer). Idempotent for the
    /// caller's purposes; the UI handler tolerates repeats.
    private func setConnection(_ new: ConnectionSnapshot) {
        connection = new
    }

    /// Sets up ADB forwarding and starts all three channels **listening** so the
    /// client can connect to any of them without a bind race. The video pipeline
    /// itself (virtual display + encoder + capture loop) is deferred until the
    /// control channel negotiates a config, at which point `bootStreaming` runs.
    ///
    /// The `config` argument is only a fallback default; the resolution/bitrate that
    /// actually drive the display and encoder come from the negotiated config passed
    /// to `bootStreaming` via `onStreamStart`.
    public func start(config: DisplayConfig) async throws {
        // Fresh stacks/capturer each start (see property comment). Without this, a
        // Stop → Start cycle reuses already-consumed AsyncStreams and the next client's
        // handshake is never read → the tablet hangs on "connecting".
        // USB is always present (loopback, plaintext, no PIN). The LAN stack (TLS + PIN)
        // is added only when the user opts into Wi-Fi serving; both then listen at once,
        // so a USB client is never forced through the LAN stack's TLS/PIN.
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

        // Request Accessibility up front so injected touches actually take effect and
        // the app is listed in System Settings > Privacy & Security > Accessibility.
        // Prompts only when not yet trusted; no-op once granted.
        _ = CGEventInjector.requestAccessibility()

        // USB(ADB): keep the reverse tunnel reconciled against device presence for the
        // whole session. A one-shot setup here would silently fail (and never retry)
        // if the device isn't ready yet, or would be lost on a re-plug — both of which
        // leave the tablet unable to reach the Mac after "waiting for device".
        await portForwardingWatcher.start()

        // Advertise the LAN control channel over Bonjour so a tablet can find the Mac
        // without a typed IP. USB (loopback) never advertises. The TXT record carries
        // the OS version shown on the tablet's server card.
        if let lan {
            let version = ProcessInfo.processInfo.operatingSystemVersion
            let osVersion = "macOS \(version.majorVersion).\(version.minorVersion)"
            lan.controlServer.advertiseBonjour(
                serviceType: ProtocolConstants.bonjourServiceType,
                osVersion: osVersion
            )
        }

        // Start every stack's three servers listening up front so that, the moment a
        // control channel sends START_STREAM, the client can immediately connect to the
        // matching video/input sockets — no bind race. The pipeline is wired later in
        // bootStreaming, once the negotiated config is known.
        //
        // Failure-atomic: the watcher and any already-bound listener are started before
        // a bind can throw (e.g. a port still held from a prior run). If any bind fails,
        // roll everything back before rethrowing — otherwise the watcher's 1s poll loop
        // and a bound socket would linger with no teardown path (the caller only flips
        // UI state on catch, it does not know to stop us).
        do {
            try await usb.startListening()
            try await lan?.startListening()
        } catch {
            await portForwardingWatcher.stop()
            await usb.stop()
            await lan?.stop()
            // Match stop()'s teardown: if the watcher already applied `adb reverse`
            // before the bind failed, remove it so a failed start leaves nothing behind.
            try? await adbManager.removePortForwarding()
            usbStack = nil
            lanStack = nil
            setConnection(.stopped)
            throw error
        }

        // All sockets are bound and listening — the server is officially up and waiting
        // for a client. `isRunning` derives from this, so there is no separate flag.
        setConnection(.waiting)

        // One control channel per stack. On successful negotiation, ControlChannelUseCase
        // invokes onStreamStart with the negotiated config BEFORE sending START_STREAM, so
        // the virtual display and encoder exist by the time the client connects to the
        // video channel. Negotiation completion moves the state to `.connected`; the
        // control keep-alive is decoupled from state (see startControlChannel).
        startControlChannel(for: usb)
        if let lan { startControlChannel(for: lan) }
    }

    /// Wires and runs the control channel for `stack`. LAN requires PIN pairing; its key
    /// is derived from the CURRENT displayed PIN at each connection, so a rotated PIN is
    /// honored without restarting the server. USB returns nil and skips auth. The stream
    /// boot is routed back to this stack by `kind`, resolved on the MainActor so the
    /// closures stay value-only and Sendable-safe.
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
                // The control keep-alive is intentionally DECOUPLED from UI state. Its 3s
                // heartbeat false-expires during the connect burst (heavy decoder init on
                // the tablet) while video keeps flowing, so it must not end the session.
                // Session end is driven by the video stream dying (see handleStreamEnded).
                // The keep-alive still loops to accept the next client on this stack.
                Log.info(.server, "control keep-alive lapsed on \(kind) (UI unaffected; video liveness drives state)")
            }
        )
        tasks.append(Task { try? await control.run() })
    }

    // MARK: - Connection-state transitions (MainActor)

    /// A client finished handshake + config negotiation on `kind`: record the session and
    /// enter `.connected`. This is the initial-connect trigger; a later video-only
    /// reconnect re-enters via `handleClientPresent` using the stored session.
    private func handleClientConnected(transport kind: TransportKind, info: ClientInfo, config: DisplayConfig) {
        guard isRunning else { return }
        activeSession = (info, config, kind)
        connectedTransports.insert(kind)
        Log.info(.server, "client connected on \(kind): \(info.deviceModel) \(config.width)x\(config.height) @\(config.fps) codec=\(config.codec); connected=\(connectedTransports)")
        setConnection(.connected(info, config, kind))
    }

    /// The video pipeline for `kind` (re)established a client connection. Re-enters
    /// `.connected` with the stored session — this is what heals a video-only reconnect
    /// (e.g. the tablet returning from the background) that has no new handshake.
    /// `generation` ignores a stale callback from a pipeline replaced by a reconnect.
    private func handleClientPresent(transport kind: TransportKind, generation: Int) {
        guard generation == streamGeneration, isRunning, let session = activeSession else { return }
        connectedTransports.insert(kind)
        setConnection(.connected(session.info, session.config, session.kind))
    }

    /// The video stream for `kind` died (client gone). Ends the session for that
    /// transport, returning to `.waiting` once no transport is streaming. `generation`
    /// guards against a stale callback from a pipeline already replaced by a reconnect.
    private func handleStreamEnded(transport kind: TransportKind, generation: Int) {
        guard generation == streamGeneration else { return }
        guard isRunning else { return }
        guard connectedTransports.remove(kind) != nil else { return }
        Log.info(.server, "video session ended on \(kind); remaining=\(connectedTransports)")
        guard connectedTransports.isEmpty else { return }
        setConnection(.waiting)
    }

    /// Creates and starts the video/input pipeline for the negotiated `config`, bound to
    /// the `transport` stack whose control channel negotiated it. Called from the control
    /// channel's `onStreamStart` hook before START_STREAM is sent, so the display and
    /// encoder are ready before the client connects to the (already-listening) video
    /// channel.
    ///
    /// The `isBooting` guard serializes overlapping boots. With one tablet (the supported
    /// case) only one stack ever negotiates; if a second stack negotiated while streaming,
    /// it would take the shared pipeline over — acceptable, since there is a single Mac
    /// desktop to mirror.
    private func bootStreaming(config: DisplayConfig, transport kind: TransportKind) async throws {
        guard !isBooting else { return }
        isBooting = true
        defer { isBooting = false }

        guard let stack = (kind == .usb ? usbStack : lanStack) else { return }

        // Bump the pipeline generation FIRST, before tearing the old pipeline down. The
        // old pipeline's teardown makes its streamToClient send fail with a (non-cancel)
        // error, which fires onClientGone; stamping the new generation now ensures that
        // stale callback is filtered by handleStreamEnded and can't flip a live,
        // re-negotiating session back to .waiting (which would also reset uptime).
        streamGeneration += 1
        let generation = streamGeneration

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
            await stack.videoServer.stop()
            await stack.inputServer.stop()
            if resolutionChanged {
                await displayManager.destroyDisplay()
            }
            // Settle: let the OS release the video/input ports and the private virtual
            // display API quiesce before rebinding / recreating.
            try? await Task.sleep(nanoseconds: 300_000_000)

            stack.videoServer = TCPServer()
            stack.inputServer = TCPServer()
            screenCapturer = SCKScreenCapturer()
            try await stack.videoServer.start(port: stack.videoPort, scope: stack.scope)
            try await stack.inputServer.start(port: stack.inputPort, scope: stack.scope)
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

        // Input channel: receive touch → inject.
        let receiveInput = ReceiveInputUseCase(receiver: stack.inputServer, injector: injector, displayID: displayID)
        pipelineTasks.append(Task { try? await receiveInput.run() })

        // Video channel: capture → encode → stream. `generation` (bumped at the top of
        // this boot) tags both liveness callbacks so a stale one from a superseded
        // pipeline is ignored. onClientPresent re-enters .connected on a (re)connection;
        // onClientGone ends the session when the client's video socket dies for good.
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

    /// Stops all channels and tears down the virtual display and ADB forwarding.
    public func stop() async {
        // Flip to .stopped first so `isRunning` is false and any in-flight callback from
        // a tearing-down control channel or video pipeline is suppressed.
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
