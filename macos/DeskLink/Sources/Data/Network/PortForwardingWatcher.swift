import Foundation

/// Keeps the ADB reverse tunnel (device localhost -> Mac server) alive for as long
/// as the server is running, by reconciling it against the device's presence.
///
/// Why this exists: `adb reverse` only succeeds while a connected, authorized device
/// is attached, and the mappings are dropped whenever the device re-enumerates
/// (unplug/replug, adbd restart, re-authorization). Setting the tunnel up once at
/// server start is therefore not enough: if the user starts the server before the
/// device is ready ("waiting for device") or replugs the cable, the tunnel is gone
/// and the tablet can no longer reach the Mac.
///
/// The watcher polls `isDeviceConnected()` and:
/// - establishes the tunnel the first time a device is seen,
/// - leaves it untouched while the same device stays present (idempotent),
/// - resets when the device disappears so the tunnel is re-applied on reappearance,
/// - retries on the next poll if `setupPortForwarding()` fails (device still settling).
public actor PortForwardingWatcher {

    /// Default poll cadence. A device appearing or re-plugging is a human-scale event,
    /// so one second is responsive enough without spamming `adb`. Public because it is
    /// referenced by the public initializer's default argument.
    public static let defaultPollIntervalMillis: UInt64 = 1000

    private let adb: ADBManaging
    private let pollIntervalNanos: UInt64

    /// True while the reverse tunnel is believed to be applied for the current device.
    private var established = false
    private var loop: Task<Void, Never>?

    public init(adb: ADBManaging, pollIntervalMillis: UInt64 = PortForwardingWatcher.defaultPollIntervalMillis) {
        self.adb = adb
        self.pollIntervalNanos = pollIntervalMillis * 1_000_000
    }

    /// Starts polling. Safe to call once per server-start; a second call while already
    /// running is a no-op.
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

    /// Stops polling. Does not remove the tunnel itself — teardown of the reverse
    /// mapping is the caller's responsibility (`ADBManaging.removePortForwarding`).
    public func stop() {
        loop?.cancel()
        loop = nil
        established = false
    }

    /// Reconciles the tunnel against the current device presence exactly once.
    /// Returns whether the tunnel is established after this pass. Exposed for tests;
    /// the run loop calls it on each poll.
    @discardableResult
    func reconcileOnce() async -> Bool {
        let deviceConnected = await adb.isDeviceConnected()

        guard deviceConnected else {
            // The device (and with it any reverse mapping) is gone. Reset so the
            // tunnel is re-applied when a device next appears.
            established = false
            return false
        }

        guard !established else { return true }

        do {
            try await adb.setupPortForwarding()
            established = true
        } catch {
            // Device is present but the mapping could not be applied yet (e.g. adbd
            // still settling right after enumeration). Leave `established` false; the
            // next poll retries. This retry IS the correct design here, not a way to
            // hide a bug, hence it is not swallowed silently.
            Log.error(.adb, "adb reverse setup failed, will retry: \(error)")
        }
        return established
    }
}
