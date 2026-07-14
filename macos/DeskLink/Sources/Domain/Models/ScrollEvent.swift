import Foundation

public struct ScrollEvent: Equatable, Sendable {
    public let deltaX: Float
    public let deltaY: Float

    public init(deltaX: Float, deltaY: Float) {
        self.deltaX = deltaX
        self.deltaY = deltaY
    }

    public static let serializedSize = 8
}
