import Foundation

/// User-configurable transport settings, persisted in `UserDefaults` (mirrors the
/// `Log.isVerbose` pattern — a single source of truth, thread-safe, no duplicated state).
///
/// `wifiEnabled` gates the LAN listener: when true, the servers bind all interfaces so
/// tablets on the same network can connect directly. This path is plaintext and
/// unauthenticated in this phase (see docs/WIFI_TRANSPORT_DESIGN.md), so it defaults to
/// false — loopback-only (USB via `adb reverse`), with no local-network exposure — and
/// must be turned on explicitly. The value is read when the server starts, so changes
/// take effect on the next Start.
enum TransportSettings {

    /// Key for the persisted "Allow Wi-Fi (LAN) connections" preference.
    static let wifiEnabledDefaultsKey = "transportWifiEnabled"

    static var wifiEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: wifiEnabledDefaultsKey) }
        set { UserDefaults.standard.set(newValue, forKey: wifiEnabledDefaultsKey) }
    }
}
