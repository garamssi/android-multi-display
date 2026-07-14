import Foundation

public enum MessageType: UInt8, Sendable {
    case handshakeRequest = 0x01
    case handshakeResponse = 0x02
    case configRequest = 0x03
    case configResponse = 0x04
    case startStream = 0x05
    case stopStream = 0x06
    case ping = 0x07
    case pong = 0x08
    case error = 0x09
    case disconnect = 0x0A
    case bitrateUpdate = 0x0B
    case configUpdate = 0x0C
    case authChallenge = 0x0D
    case authResponse = 0x0E
    case authConfirm = 0x0F

    case videoFrame = 0x10
    case videoConfig = 0x11
    case keyframeRequest = 0x12

    case touchEvent = 0x20
    case touchBatch = 0x21
    case scroll = 0x22
    case pointerButton = 0x23
}
