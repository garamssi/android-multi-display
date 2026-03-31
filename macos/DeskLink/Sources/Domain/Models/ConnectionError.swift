import Foundation

public enum ConnectionError: Int, Error, Sendable {
    // Connection errors (1000-1099)
    case refused = 1000
    case protocolMismatch = 1001
    case timeout = 1002
    case lost = 1003

    // Codec errors (1100-1199)
    case encoderInitFailed = 1100
    case encoderFailed = 1101
    case decoderInitFailed = 1102
    case decoderFailed = 1103
    case codecNotSupported = 1104

    // Display errors (1200-1299)
    case displayCreateFailed = 1200
    case displayCaptureFailed = 1201
    case displayResolutionInvalid = 1202

    // Input errors (1300-1399)
    case inputInjectionFailed = 1300
    case inputPermissionDenied = 1301

    // Config errors (1400-1499)
    case configInvalid = 1400
    case configNegotiationFailed = 1401
}
