import Foundation

public struct TouchEvent: Sendable, Equatable {
    public let action: Action
    public let x: Float
    public let y: Float
    public let pressure: UInt16
    public let pointerId: UInt8
    public let timestampUs: Int64

    public init(action: Action, x: Float, y: Float, pressure: UInt16, pointerId: UInt8, timestampUs: Int64) {
        precondition(x >= 0 && x <= 1, "x must be normalized (0.0-1.0), got \(x)")
        precondition(y >= 0 && y <= 1, "y must be normalized (0.0-1.0), got \(y)")
        self.action = action
        self.x = x
        self.y = y
        self.pressure = pressure
        self.pointerId = pointerId
        self.timestampUs = timestampUs
    }

    public enum Action: UInt8, Sendable {
        case down = 0x00
        case up = 0x01
        case move = 0x02
        case cancel = 0x03
    }

    public static let serializedSize = 20 // 1 + 4 + 4 + 2 + 1 + 8
}
