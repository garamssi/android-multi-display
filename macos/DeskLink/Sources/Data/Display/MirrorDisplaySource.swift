import CoreGraphics
import Foundation

// Mirror mode: streams a display that already exists, so nothing is created or destroyed.
//
// The requested resolution is ignored — a real display cannot be resized to match it, and
// claiming otherwise would encode a frame size that does not match the pixels.
//
// The size comes from `CGDisplayBounds`, which reports POINTS. On a Retina display that is
// half the physical pixels, so the picture is upscaled on the tablet. Capturing at the
// physical size (`CGDisplayCopyDisplayMode().pixelWidth`) would be sharper but quadruples
// the captured pixels per frame, and the frame-rate ceiling of the current pipeline has
// not been explained yet — so that change waits until it is, rather than being bundled in
// here where a regression could not be attributed.
public final class MirrorDisplaySource: StreamSourceProviding {

    private let displayID: UInt32
    private let displaySize: @Sendable (UInt32) -> CGSize

    public init(
        displayID: UInt32,
        displaySize: @escaping @Sendable (UInt32) -> CGSize = { CGDisplayBounds($0).size }
    ) {
        self.displayID = displayID
        self.displaySize = displaySize
    }

    public func prepare(config: DisplayConfig) async throws -> StreamSource {
        let size = displaySize(displayID)
        // Rounded down to even: chroma-subsampled encoders reject odd dimensions.
        let width = Self.toEven(Int(size.width))
        let height = Self.toEven(Int(size.height))

        guard width > 0, height > 0 else {
            Log.error(.capture, "mirror: display \(displayID) reported no usable size")
            throw ConnectionError.displayCaptureFailed
        }

        return StreamSource(
            displayID: displayID,
            captureWidth: width,
            captureHeight: height,
            acceptsInput: false
        )
    }

    // Nothing to release: the display belongs to the user.
    public func teardown() async {}

    private static func toEven(_ value: Int) -> Int { value - (value % 2) }
}
