import Foundation

// adb reverse mappings drop whenever the device re-enumerates (replug, adbd restart); setup-once is not enough, so reconcile continuously against device presence.
public actor PortForwardingWatcher {

    public static let defaultPollIntervalMillis: UInt64 = 1000

    private let adb: ADBManaging
    private let pollIntervalNanos: UInt64

    private var established = false
    private var loop: Task<Void, Never>?

    public init(adb: ADBManaging, pollIntervalMillis: UInt64 = PortForwardingWatcher.defaultPollIntervalMillis) {
        self.adb = adb
        self.pollIntervalNanos = pollIntervalMillis * 1_000_000
    }

    public func start() {
        guard loop == nil else { return }
        established = false
        let interval = pollIntervalNanos
        loop = Task { [weak self] in
            while !Task.isCancelled {
                await self?.reconcileOnce()
                try? await Task.sleep(nanoseconds: interval)
            }
        }
    }

    public func stop() {
        loop?.cancel()
        loop = nil
        established = false
    }

    @discardableResult
    func reconcileOnce() async -> Bool {
        let deviceConnected = await adb.isDeviceConnected()

        guard deviceConnected else {
            established = false
            return false
        }

        guard !established else { return true }

        do {
            try await adb.setupPortForwarding()
            established = true
        } catch {
            // Mapping can fail right after enumeration (adbd still settling); leave `established` false and let the next poll retry. Legitimate reconciliation, not error hiding.
            Log.error(.adb, "adb reverse setup failed, will retry: \(error)")
        }
        return established
    }
}
