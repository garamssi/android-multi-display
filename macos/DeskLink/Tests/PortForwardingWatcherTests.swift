import XCTest
@testable import DeskLink

/// Verifies the ADB reverse tunnel is (re)established as the device comes and goes,
/// rather than being set up once and forgotten.
///
/// Root cause under test: `ServerCoordinator.start()` used to call
/// `try? await adbManager.setupPortForwarding()` exactly once and swallow any error.
/// If the device was not yet connected/authorized at that instant, the reverse
/// tunnel was never created and never retried, so a device that appeared later
/// ("waiting for device" for too long) could not connect.
final class PortForwardingWatcherTests: XCTestCase {

    func testDoesNotForwardWhileNoDeviceIsPresent() async {
        let adb = FakeADBManaging(connected: false)
        let watcher = PortForwardingWatcher(adb: adb)

        let established = await watcher.reconcileOnce()

        XCTAssertFalse(established)
        XCTAssertEqual(adb.setupCount, 0)
    }

    func testForwardsOnceWhenDeviceIsPresent() async {
        let adb = FakeADBManaging(connected: true)
        let watcher = PortForwardingWatcher(adb: adb)

        let established = await watcher.reconcileOnce()

        XCTAssertTrue(established)
        XCTAssertEqual(adb.setupCount, 1)
    }

    func testDoesNotReForwardWhileDeviceStaysPresent() async {
        let adb = FakeADBManaging(connected: true)
        let watcher = PortForwardingWatcher(adb: adb)

        _ = await watcher.reconcileOnce()
        _ = await watcher.reconcileOnce()
        _ = await watcher.reconcileOnce()

        // Idempotent: one successful setup, not one per poll.
        XCTAssertEqual(adb.setupCount, 1)
    }

    /// The core "waiting for device" fix: the device is absent at first (setup must
    /// not happen), then appears later — the tunnel must be established at that point.
    func testForwardsWhenDeviceAppearsLater() async {
        let adb = FakeADBManaging(connected: false)
        let watcher = PortForwardingWatcher(adb: adb)

        _ = await watcher.reconcileOnce()
        XCTAssertEqual(adb.setupCount, 0)

        adb.setConnected(true)
        let established = await watcher.reconcileOnce()

        XCTAssertTrue(established)
        XCTAssertEqual(adb.setupCount, 1)
    }

    /// A re-plug (present -> absent -> present) must re-apply the reverse tunnel,
    /// because adb drops reverse mappings when the device re-enumerates.
    func testReForwardsAfterDeviceRePlug() async {
        let adb = FakeADBManaging(connected: true)
        let watcher = PortForwardingWatcher(adb: adb)

        _ = await watcher.reconcileOnce()            // present -> setup #1
        adb.setConnected(false)
        _ = await watcher.reconcileOnce()            // absent -> reset
        adb.setConnected(true)
        _ = await watcher.reconcileOnce()            // present again -> setup #2

        XCTAssertEqual(adb.setupCount, 2)
    }

    /// If setup fails while the device is present (e.g. adbd still settling), the
    /// watcher must not mark itself established and must retry on the next poll.
    func testRetriesAfterSetupFailure() async {
        let adb = FakeADBManaging(connected: true)
        adb.setupShouldThrow = true
        let watcher = PortForwardingWatcher(adb: adb)

        let firstAttempt = await watcher.reconcileOnce()
        XCTAssertFalse(firstAttempt)
        XCTAssertEqual(adb.setupCount, 1) // attempted

        adb.setupShouldThrow = false
        let secondAttempt = await watcher.reconcileOnce()

        XCTAssertTrue(secondAttempt)
        XCTAssertEqual(adb.setupCount, 2) // retried and succeeded
    }
}

// MARK: - Test double

private final class FakeADBManaging: ADBManaging, @unchecked Sendable {
    private let lock = NSLock()
    private var connected: Bool
    private(set) var setupCount = 0
    private(set) var removeCount = 0
    var setupShouldThrow = false

    private let statusStream: AsyncStream<Bool>
    private let statusContinuation: AsyncStream<Bool>.Continuation

    init(connected: Bool) {
        self.connected = connected
        var continuation: AsyncStream<Bool>.Continuation!
        statusStream = AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation = $0 }
        statusContinuation = continuation
    }

    func setConnected(_ value: Bool) {
        lock.withLock { connected = value }
    }

    func setupPortForwarding() async throws {
        lock.withLock { setupCount += 1 }
        // Tests toggle setupShouldThrow between sequential awaits, so a plain read is
        // race-free here.
        if setupShouldThrow {
            throw ConnectionError.refused
        }
    }

    func removePortForwarding() async throws {
        lock.withLock { removeCount += 1 }
    }

    func isDeviceConnected() async -> Bool {
        lock.withLock { connected }
    }

    var deviceStatusChanges: AsyncStream<Bool> { statusStream }
}
