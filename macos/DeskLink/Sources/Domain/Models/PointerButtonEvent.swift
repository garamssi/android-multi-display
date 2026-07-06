import Foundation

/// A pointer button press or release at a normalized position, used to map a
/// single-finger long-press on the tablet to a right-click on the Mac. Coordinates
/// follow the same normalized [0,1] convention as `TouchEvent` (fraction of the
/// virtual display). A full click is two events: `.down` then `.up`.
public struct PointerButtonEvent: Equatable, Sendable {

    /// Which mouse button the event targets.
    public enum Button: UInt8, Sendable {
        case left = 0x00
        case right = 0x01
    }

    /// Whether the button is being pressed or released.
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

    /// Wire payload size: Button(1) + Action(1) + X(f32 BE) + Y(f32 BE) = 10 bytes.
    public static let serializedSize = 10
}
