import Foundation

/// A scroll gesture delta, expressed as a fraction of the display dimension
/// (device-independent, like `TouchEvent` coordinates). `deltaX` > 0 means the
/// fingers moved right; `deltaY` > 0 means the fingers moved down. The injector
/// converts these to a pixel scroll against the display bounds and applies the
/// natural-scroll direction sign.
public struct ScrollEvent: Equatable, Sendable {
    public let deltaX: Float
    public let deltaY: Float

    public init(deltaX: Float, deltaY: Float) {
        self.deltaX = deltaX
        self.deltaY = deltaY
    }

    /// Wire payload size: DeltaX(f32 BE) + DeltaY(f32 BE) = 8 bytes.
    public static let serializedSize = 8
}
