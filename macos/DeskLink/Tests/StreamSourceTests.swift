import CoreGraphics
import XCTest
@testable import DeskLink

/// What the server streams, and how the two display modes produce it.
///
/// Extend adds a virtual display sized to the negotiated config; mirror streams a display
/// that already exists and cannot be resized. Keeping that difference behind one value
/// type is what stops the mode from leaking into the capture, encode and input paths —
/// each of those only needs a display id and a size.
final class StreamSourceTests: XCTestCase {

    // MARK: - Mirror

    /// Mirror does not create anything: it reports the display it was asked to stream.
    func testMirrorReportsTheRequestedDisplay() async throws {
        let source = MirrorDisplaySource(
            displayID: 42,
            displaySize: { _ in CGSize(width: 1512, height: 982) }
        )
        let stream = try await source.prepare(config: DisplayConfig())
        XCTAssertEqual(stream.displayID, 42)
        XCTAssertEqual(stream.captureWidth, 1512)
        XCTAssertEqual(stream.captureHeight, 982)
    }

    /// Input must be refused in mirror mode: the touches would move the cursor on the
    /// screen the person at the Mac is using.
    func testMirrorRefusesInput() async throws {
        let source = MirrorDisplaySource(
            displayID: 1,
            displaySize: { _ in CGSize(width: 1512, height: 982) }
        )
        let stream = try await source.prepare(config: DisplayConfig())
        XCTAssertFalse(stream.acceptsInput)
    }

    /// The requested resolution is IGNORED: a real display cannot be resized to match it,
    /// and pretending otherwise would encode a size that does not match the pixels.
    func testMirrorIgnoresRequestedResolution() async throws {
        let source = MirrorDisplaySource(
            displayID: 1,
            displaySize: { _ in CGSize(width: 1512, height: 982) }
        )
        let requested = DisplayConfig(width: 3200, height: 2000)
        let stream = try await source.prepare(config: requested)
        XCTAssertEqual(stream.captureWidth, 1512)
        XCTAssertEqual(stream.captureHeight, 982)
    }

    /// A display that reports no size cannot be streamed; failing here beats configuring a
    /// zero-sized capture.
    func testMirrorRejectsAZeroSizedDisplay() async {
        let source = MirrorDisplaySource(
            displayID: 1,
            displaySize: { _ in CGSize(width: 0, height: 0) }
        )
        do {
            _ = try await source.prepare(config: DisplayConfig())
            XCTFail("expected a failure for a zero-sized display")
        } catch {
            XCTAssertEqual(error as? ConnectionError, .displayCaptureFailed)
        }
    }

    /// Odd dimensions are rounded down: chroma-subsampled encoders reject them.
    func testMirrorRoundsToEvenDimensions() async throws {
        let source = MirrorDisplaySource(
            displayID: 1,
            displaySize: { _ in CGSize(width: 1511, height: 981) }
        )
        let stream = try await source.prepare(config: DisplayConfig())
        XCTAssertEqual(stream.captureWidth, 1510)
        XCTAssertEqual(stream.captureHeight, 980)
    }

    /// Teardown must not destroy anything — the display belongs to the user, not to us.
    func testMirrorTeardownIsHarmless() async throws {
        let source = MirrorDisplaySource(
            displayID: 1,
            displaySize: { _ in CGSize(width: 1512, height: 982) }
        )
        _ = try await source.prepare(config: DisplayConfig())
        await source.teardown()
        // Still usable afterwards: nothing was released.
        let again = try await source.prepare(config: DisplayConfig())
        XCTAssertEqual(again.displayID, 1)
    }

    // MARK: - Extend

    /// Extend creates a virtual display at the negotiated size and allows input.
    func testExtendCreatesADisplaySizedToTheConfig() async throws {
        let manager = SpyVirtualDisplayManager(displayID: 7)
        let source = VirtualDisplaySource(displayManager: manager)
        let config = DisplayConfig(width: 2560, height: 1600)

        let stream = try await source.prepare(config: config)

        XCTAssertEqual(manager.createdConfigs.map(\.width), [2560])
        XCTAssertEqual(stream.displayID, 7)
        XCTAssertEqual(stream.captureWidth, 2560)
        XCTAssertEqual(stream.captureHeight, 1600)
        XCTAssertTrue(stream.acceptsInput)
    }

    /// The virtual display must be released, or it lingers in the user's display
    /// arrangement after the session ends.
    func testExtendTeardownDestroysTheDisplay() async throws {
        let manager = SpyVirtualDisplayManager(displayID: 7)
        let source = VirtualDisplaySource(displayManager: manager)
        _ = try await source.prepare(config: DisplayConfig())
        await source.teardown()
        XCTAssertEqual(manager.destroyCount, 1)
    }

    /// If the private display API falls back to a different mode, the capture size must
    /// follow the ACTUAL display, not the request — otherwise a smaller picture is encoded
    /// into a larger frame and the bitrate is spent on nothing.
    func testExtendUsesTheActualResolutionWhenItDiffers() async throws {
        let manager = SpyVirtualDisplayManager(displayID: 7)
        manager.actualResolution = (width: 1600, height: 1000)
        let source = VirtualDisplaySource(displayManager: manager)

        let stream = try await source.prepare(config: DisplayConfig(width: 3200, height: 2000))

        XCTAssertEqual(stream.captureWidth, 1600)
        XCTAssertEqual(stream.captureHeight, 1000)
    }

    // MARK: - Doubles

    private final class SpyVirtualDisplayManager: VirtualDisplayManaging, @unchecked Sendable {
        private let lock = NSLock()
        private let id: UInt32
        private var configs: [DisplayConfig] = []
        private var destroys = 0

        var actualResolution: (width: Int, height: Int)?

        init(displayID: UInt32) { self.id = displayID }

        var createdConfigs: [DisplayConfig] { lock.withLock { configs } }
        var destroyCount: Int { lock.withLock { destroys } }

        var displayID: UInt32 { id }
        var activeResolution: (width: Int, height: Int)? { actualResolution }

        func createDisplay(config: DisplayConfig) async throws {
            lock.withLock { configs.append(config) }
        }

        func destroyDisplay() async {
            lock.withLock { destroys += 1 }
        }
    }
}
