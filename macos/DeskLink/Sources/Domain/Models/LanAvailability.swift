import Foundation

/// Whether the Wi-Fi (LAN) channels can be served, and if not, why.
///
/// Serving them without a TLS identity is not a lesser option: the tablet wraps LAN sockets in
/// TLS, so a plaintext listener cannot be talked to at all, and if a client did accept it the
/// pairing PIN would cross the network in the clear. The old behaviour started that listener
/// anyway and said so in one log line, which reads to the user as "Wi-Fi is on but the PIN
/// never works".
public enum LanAvailability: Sendable, Equatable {
    case enabled
    case disabledByPreference
    case blockedWithoutTlsIdentity

    public var bindsListener: Bool { self == .enabled }

    /// What to tell the user. Nil when nothing is wrong.
    public var problemDescription: String? {
        switch self {
        case .enabled, .disabledByPreference:
            return nil
        case .blockedWithoutTlsIdentity:
            return "Wi-Fi needs a TLS certificate. Run scripts/create_tls_cert.sh, then start the server again."
        }
    }

    public static func decide(wifiEnabled: Bool, hasTlsIdentity: Bool) -> LanAvailability {
        // The preference is checked first: with Wi-Fi off, a missing identity is not what is
        // stopping anything, and naming it would send the user to fix the wrong thing.
        guard wifiEnabled else { return .disabledByPreference }
        return hasTlsIdentity ? .enabled : .blockedWithoutTlsIdentity
    }
}
