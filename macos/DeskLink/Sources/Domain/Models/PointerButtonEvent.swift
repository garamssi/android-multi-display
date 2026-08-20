import Foundation

public struct PointerButtonEvent: Equatable, Sendable {

    public enum Button: UInt8, Sendable {
        case left = 0x00
        case right = 0x01
    }

    public enum Action: UInt8, Sendable {
        case down = 0x00
        case up = 0x01
    }

    public let button: Button
    public let action: Action
    public let x: Float
    public let y: Float

    public init(button: Button, action: Action, x: Float, y: Float) {
        self.button = button
        self.action = action
        self.x = x
        self.y = y
    }

    public static let serializedSize = 10
}
