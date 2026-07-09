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
