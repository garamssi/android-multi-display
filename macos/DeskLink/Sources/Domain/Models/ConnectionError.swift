import Foundation

public enum ConnectionError: Int, Error, Sendable {
    case refused = 1000
    case protocolMismatch = 1001
    case timeout = 1002
    case lost = 1003

    case encoderInitFailed = 1100
    case encoderFailed = 1101
    case decoderInitFailed = 1102
    case decoderFailed = 1103
    case codecNotSupported = 1104

    case displayCreateFailed = 1200
    case displayCaptureFailed = 1201
    case displayResolutionInvalid = 1202

    case inputInjectionFailed = 1300
    case inputPermissionDenied = 1301

    case configInvalid = 1400
    case configNegotiationFailed = 1401
}
