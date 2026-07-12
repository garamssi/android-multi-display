import Foundation
import Observation

/// High-level server state surfaced to the menu-bar UI.
public enum ServerStatus: Sendable, Equatable {
    /// Server stopped — nothing listening.
    case disconnected
    /// Server started and listening, but no client has completed handshake yet.
    case connecting
    /// A client has finished handshake/config negotiation and is streaming.
    case connected
}

/// Observable presentation model for `StatusMenuView`.
///
/// Owns the `ServerCoordinator` and translates its lifecycle callbacks into the
/// reactive state the popover renders. It only *observes* the coordinator — all
/// streaming/handshake/ADB behaviour stays inside the coordinator and its use cases.
@MainActor
@Observable
public final class ServerViewModel {

    // MARK: - Published state

    /// Drives the whole popover layout (disconnected vs connecting vs connected).
    public private(set) var status: ServerStatus = .disconnected

    /// Connected device model (from the handshake `ClientInfo`). `nil` when idle.
    public private(set) var deviceName: String?

    /// Physical link. Always "USB" — the exact bus version isn't negotiated, so the
    /// spec's "USB 3.2" is shown as a sensible constant.
    public var link: String = "USB"

    /// Whether the running server is also listening on Wi-Fi (LAN). Captured at [start]
    /// from the persisted opt-in — the same value the coordinator resolves its listener
    /// scope from — so the UI reflects the live listener, not a later toggle change that
    /// only takes effect on the next start.
    public private(set) var wifiListening = false

    /// Negotiated output resolution, e.g. "2560×1600". `nil` when idle.
    public private(set) var output: String?

    /// Negotiated frame line, e.g. "60 fps · H.265". `nil` when idle.
    public private(set) var frame: String?

    /// Live uptime "HH:MM:SS", derived from `connectedAt` and ticked each second.
    public private(set) var uptime: String = "00:00:00"

    // MARK: - Private

    private let coordinator: ServerCoordinator
    private var connectedAt: Date?
    private var uptimeTimer: Task<Void, Never>?

    public init(coordinator: ServerCoordinator = ServerCoordinator()) {
        self.coordinator = coordinator
        wireCoordinator()
    }

    // MARK: - Coordinator wiring

    private func wireCoordinator() {
        // Coarse status changes: start() → .connecting, stop() → .disconnected.
        coordinator.onStatusChange = { [weak self] status in
            guard let self else { return }
            self.status = status
            if status != .connected {
                self.clearConnectionMetadata()
            }
        }

        // A client finished handshake/config negotiation and streaming began.
        coordinator.onClientConnected = { [weak self] info, config in
            self?.applyConnected(info: info, config: config)
        }

        // The active client dropped, but the server is still listening.
        coordinator.onClientDisconnected = { [weak self] in
            guard let self else { return }
            self.status = .connecting
            self.clearConnectionMetadata()
        }
    }

    // MARK: - Intents

    /// Starts the server. Optimistically flips to `.connecting`; reverts to
    /// `.disconnected` if the coordinator throws while binding.
    public func start() {
        guard status == .disconnected else { return }
        status = .connecting
        // Capture the listener scope for this session up front (the coordinator resolves
        // its scope from the same flag), so the banner names the transports actually bound.
        wifiListening = TransportSettings.wifiEnabled
        Task { [weak self] in
            guard let self else { return }
            do {
                try await self.coordinator.start(config: DisplayConfig())
            } catch {
                self.status = .disconnected
                self.clearConnectionMetadata()
            }
        }
    }

    /// Stops the server and returns to the disconnected layout.
    public func stop() {
        Task { [weak self] in
            await self?.coordinator.stop()
        }
    }

    // MARK: - State transitions

    private func applyConnected(info: ClientInfo, config: DisplayConfig) {
        let model = info.deviceModel
        deviceName = (model.isEmpty || model == "Unknown") ? info.clientName : model
        output = "\(config.width)×\(config.height)"
        frame = "\(config.fps) fps · \(config.codec == .hevc ? "H.265" : "H.264")"
        connectedAt = Date()
        status = .connected
        startUptimeTimer()
    }

    private func clearConnectionMetadata() {
        deviceName = nil
        output = nil
        frame = nil
        connectedAt = nil
        uptime = "00:00:00"
        stopUptimeTimer()
    }

    // MARK: - Uptime timer

    private func startUptimeTimer() {
        stopUptimeTimer()
        updateUptime()
        uptimeTimer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { break }
                self?.updateUptime()
            }
        }
    }

    private func stopUptimeTimer() {
        uptimeTimer?.cancel()
        uptimeTimer = nil
    }

    private func updateUptime() {
        guard let connectedAt else {
            uptime = "00:00:00"
            return
        }
        let elapsed = max(0, Int(Date().timeIntervalSince(connectedAt)))
        let h = elapsed / 3600
        let m = (elapsed % 3600) / 60
        let s = elapsed % 60
        uptime = String(format: "%02d:%02d:%02d", h, m, s)
    }
}
