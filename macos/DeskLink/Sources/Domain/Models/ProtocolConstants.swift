import Foundation

public enum ProtocolConstants {
    public static let protocolVersion = 1

    // Ports
    public static let portControl: UInt16 = 7100
    public static let portVideo: UInt16 = 7101
    public static let portInput: UInt16 = 7102

    // Bonjour / NSD service type advertised on the control port when Wi-Fi (LAN) is
    // enabled, so a tablet can discover the Mac without a typed IP. This string is a
    // cross-platform contract: the Android client discovers the same type. The client
    // only needs the resolved host; the video/input ports are the fixed constants above.
    public static let bonjourServiceType = "_desklink._tcp"

    /// Bonjour TXT key the Mac advertises its OS version under (e.g. "macOS 15.4").
    public static let bonjourTxtKeyOS = "os"

    // LAN pairing (P3, TLS-PSK). Cross-platform contract with the Android client: both
    // sides derive the identical pre-shared key from the 6-digit PIN via HKDF-SHA256
    // (RFC 5869) over these parameters, or the TLS-PSK handshake fails. Reference +
    // golden vectors: tools/pairing_vectors.py. USB does not use any of this.
    public static let pskHkdfSalt = "desklink-pairing-v1"
    public static let pskHkdfInfo = "desklink-psk"
    public static let pskLengthBytes = 32
    public static let pskIdentity = "desklink"
    public static let pairingPinLength = 6

    // LAN mutual-auth challenge-response (P3). proof = HMAC-SHA256(pairingKey,
    // context || serverNonce || clientNonce); contexts distinguish the two directions.
    // Golden vectors: tools/protocol_vectors.py (AUTH_*).
    public static let authNonceLength = 16
    public static let authClientContext = "desklink-auth-client"
    public static let authServerContext = "desklink-auth-server"

    // Packet limits
    public static let maxPacketSize = 4 * 1024 * 1024 // 4MB

    // Timing (milliseconds)
    public static let handshakeTimeout: UInt64 = 5_000
    public static let pingInterval: UInt64 = 1_000
    public static let pingTimeout: UInt64 = 3_000
    public static let reconnectDelay: UInt64 = 1_000
    public static let reconnectMaxDelay: UInt64 = 30_000
    public static let reconnectMaxAttempts = 10
    public static let streamStartTimeout: UInt64 = 3_000

    // Socket buffer
    public static let socketBufferSize = 2 * 1024 * 1024 // 2MB
}
