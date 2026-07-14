import Foundation

public enum ProtocolConstants {
    public static let protocolVersion = 1

    public static let portControl: UInt16 = 7100
    public static let portVideo: UInt16 = 7101
    public static let portInput: UInt16 = 7102

    public static let portControlLan: UInt16 = 7110
    public static let portVideoLan: UInt16 = 7111
    public static let portInputLan: UInt16 = 7112

    public static let bonjourServiceType = "_desklink._tcp"

    public static let bonjourTxtKeyOS = "os"

    public static let pskHkdfSalt = "desklink-pairing-v1"
    public static let pskHkdfInfo = "desklink-psk"
    public static let pskLengthBytes = 32
    public static let pskIdentity = "desklink"
    public static let pairingPinLength = 6

    public static let authNonceLength = 16
    public static let authClientContext = "desklink-auth-client"
    public static let authServerContext = "desklink-auth-server"

    public static let maxPacketSize = 4 * 1024 * 1024 // 4MB

    public static let handshakeTimeout: UInt64 = 5_000
    public static let pingInterval: UInt64 = 1_000
    public static let pingTimeout: UInt64 = 3_000
    public static let reconnectDelay: UInt64 = 1_000
    public static let reconnectMaxDelay: UInt64 = 30_000
    public static let reconnectMaxAttempts = 10
    public static let streamStartTimeout: UInt64 = 3_000

    public static let socketBufferSize = 2 * 1024 * 1024 // 2MB
}
