import XCTest
@testable import DeskLink

/// Plugging in headphones changes the default output device, which pulls the sub-device
/// out from under the aggregate device the tap lives in. Without noticing, the audio
/// simply stops with no error anywhere — so the change is detected and the capture is
/// rebuilt against the new device.
final class DefaultOutputDeviceWatcherTests: XCTestCase {

    func testNoChangeReportedForTheSameDevice() {
        var watcher = DefaultOutputDeviceWatcher(currentDeviceID: 42)
        XCTAssertFalse(watcher.deviceDidChange(to: 42))
    }

    func testChangeReportedForADifferentDevice() {
        var watcher = DefaultOutputDeviceWatcher(currentDeviceID: 42)
        XCTAssertTrue(watcher.deviceDidChange(to: 43))
    }

    /// After reporting a change the watcher must adopt the new device, or every
    /// subsequent poll would report the same change again and restart capture in a loop.
    func testAdoptsTheNewDeviceAfterReporting() {
        var watcher = DefaultOutputDeviceWatcher(currentDeviceID: 42)
        XCTAssertTrue(watcher.deviceDidChange(to: 43))
        XCTAssertFalse(watcher.deviceDidChange(to: 43))
    }

    /// A transient "no device" reading (during a switch) must not be treated as a change:
    /// rebuilding against nothing would fail, and the real device appears a moment later.
    func testUnknownDeviceIsNotAChange() {
        var watcher = DefaultOutputDeviceWatcher(currentDeviceID: 42)
        XCTAssertFalse(watcher.deviceDidChange(to: DefaultOutputDeviceWatcher.unknownDeviceID))
        // The original device is still the reference, so returning to it is not a change.
        XCTAssertFalse(watcher.deviceDidChange(to: 42))
    }

    func testChangeAfterATransientUnknownIsStillDetected() {
        var watcher = DefaultOutputDeviceWatcher(currentDeviceID: 42)
        _ = watcher.deviceDidChange(to: DefaultOutputDeviceWatcher.unknownDeviceID)
        XCTAssertTrue(watcher.deviceDidChange(to: 43))
    }
}
