import XCTest
@testable import DeskLink

/// Parses `adb devices` output.
///
/// Device selection is a correctness issue: `adb reverse` fails with "more than one
/// device/emulator" when several targets are attached, which is the common case on a
/// developer machine with an emulator running. Previously any line containing "\tdevice"
/// was enough to report a device present, and the tunnel command then failed with the
/// cause reported as a bare connection refusal.
final class ADBDeviceListTests: XCTestCase {

    private func serials(_ output: String) -> [String] {
        ADBDeviceList.attachedSerials(fromDevicesOutput: output)
    }

    func testSingleAttachedDevice() {
        let output = """
            List of devices attached
            HA2ET5DZ\tdevice
            """
        XCTAssertEqual(serials(output), ["HA2ET5DZ"])
    }

    /// The header line must never be mistaken for a device.
    func testHeaderOnlyMeansNoDevices() {
        XCTAssertEqual(serials("List of devices attached\n"), [])
    }

    func testEmptyOutput() {
        XCTAssertEqual(serials(""), [])
    }

    /// An emulator alongside the tablet is exactly the case that broke `adb reverse`.
    func testEmulatorAndDeviceAreBothReported() {
        let output = """
            List of devices attached
            emulator-5554\tdevice
            HA2ET5DZ\tdevice
            """
        XCTAssertEqual(serials(output), ["emulator-5554", "HA2ET5DZ"])
    }

    /// States other than "device" cannot accept a reverse tunnel, so they are not attached.
    func testUnusableStatesAreExcluded() {
        let output = """
            List of devices attached
            HA2ET5DZ\tunauthorized
            OTHER123\toffline
            BOOTING1\tbootloader
            """
        XCTAssertEqual(serials(output), [])
    }

    func testMixedStatesReportOnlyUsableOnes() {
        let output = """
            List of devices attached
            HA2ET5DZ\tdevice
            OTHER123\tunauthorized
            """
        XCTAssertEqual(serials(output), ["HA2ET5DZ"])
    }

    /// Real output has trailing blank lines and Windows line endings from some shells.
    func testToleratesSurroundingWhitespace() {
        let output = "List of devices attached\n\nHA2ET5DZ\tdevice \n\n"
        XCTAssertEqual(serials(output), ["HA2ET5DZ"])
    }

    /// A device name containing the word "device" must not be double-counted.
    func testSerialContainingTheWordDevice() {
        XCTAssertEqual(serials("mydevice01\tdevice"), ["mydevice01"])
    }
}
