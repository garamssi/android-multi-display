import Foundation
import Observation
import AppKit

public enum ServerStatus: Sendable, Equatable {
    case disconnected
    case connecting
    case connected
}

@MainActor
@Observable
public final class ServerViewModel {

    // MARK: - Published state

    public private(set) var status: ServerStatus = .disconnected

    public private(set) var deviceName: String?

    public private(set) var link: String = "USB"

    public private(set) var wifiListening = false

    public private(set) var pairingPin: String = PairingPin.current

    public private(set) var pairingSecondsRemaining: Int = PairingPin.secondsRemaining()

    public private(set) var output: String?

    public private(set) var frame: String?

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
        coordinator.onConnectionChanged = { [weak self] snapshot in
            self?.apply(snapshot)
        }
    }

    private func apply(_ snapshot: ConnectionSnapshot) {
        switch snapshot {
        case .stopped:
            status = .disconnected
            clearConnectionMetadata()
        case .waiting:
            status = .connecting
            clearConnectionMetadata()
        case let .connected(info, config, transport):
            applyConnected(info: info, config: config, transport: transport)
        }
    }

    // MARK: - Intents

    public func start() {
        guard status == .disconnected else { return }
        status = .connecting
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

    public func stop() {
        Task { [weak self] in
            await self?.coordinator.stop()
        }
    }

    public func tickPairing() {
        guard status == .connecting, wifiListening else { return }
        PairingPin.rotateIfExpired()
        pairingPin = PairingPin.current
        pairingSecondsRemaining = PairingPin.secondsRemaining()
    }

    public func copyPairingPin() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(pairingPin, forType: .string)
    }

    // MARK: - State transitions

    private func applyConnected(info: ClientInfo, config: DisplayConfig, transport: TransportKind) {
        let model = info.deviceModel
        deviceName = (model.isEmpty || model == "Unknown") ? info.clientName : model
        link = transport.displayName
        output = "\(config.width)×\(config.height)"
        frame = "\(config.fps) fps · \(config.codec == .hevc ? "H.265" : "H.264")"
        status = .connected
        if connectedAt == nil {
            connectedAt = Date()
            startUptimeTimer()
        }
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
