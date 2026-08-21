import XCTest
@testable import DeskLink

/// The extend/mirror choice.
///
/// It lives on the Mac because it changes the Mac's own state: extend adds a display to
/// the user's arrangement and moves windows, and the server must be the one that decides
/// whether to accept input at all — a client cannot be trusted to report the mode it is in.
final class DisplayModePreferenceTests: XCTestCase {

    private var suiteName = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "DisplayModePreferenceTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    /// Extend is the default: it is what the app has always done, and mirror silently
    /// taking over the Mac's own screen would be a surprising change of behaviour.
    func testDefaultsToExtend() {
        XCTAssertEqual(DisplayModePreference(defaults: defaults).mode, .extend)
    }

    func testPersistsMirror() {
        DisplayModePreference(defaults: defaults).mode = .mirror
        XCTAssertEqual(DisplayModePreference(defaults: defaults).mode, .mirror)
    }

    func testPersistsBackToExtend() {
        let preference = DisplayModePreference(defaults: defaults)
        preference.mode = .mirror
        preference.mode = .extend
        XCTAssertEqual(DisplayModePreference(defaults: defaults).mode, .extend)
    }

    /// An unrecognised stored value must not leave the app in an undefined mode.
    func testUnknownStoredValueFallsBackToExtend() {
        defaults.set("something-else", forKey: DisplayModePreference.defaultsKey)
        XCTAssertEqual(DisplayModePreference(defaults: defaults).mode, .extend)
    }

    /// The key is part of the on-disk contract; renaming it resets everyone's choice.
    func testDefaultsKeyIsStable() {
        XCTAssertEqual(DisplayModePreference.defaultsKey, "displayMode")
    }

    /// Only mirror refuses input, and only extend creates a display. These two facts drive
    /// the whole difference downstream, so they are pinned on the mode itself.
    func testModeCapabilities() {
        XCTAssertTrue(DisplayMode.extend.acceptsInput)
        XCTAssertFalse(DisplayMode.mirror.acceptsInput)
        XCTAssertTrue(DisplayMode.extend.createsVirtualDisplay)
        XCTAssertFalse(DisplayMode.mirror.createsVirtualDisplay)
    }

    /// The wire value is part of the protocol; both sides agree on these strings.
    func testWireValues() {
        XCTAssertEqual(DisplayMode.extend.wireValue, "extend")
        XCTAssertEqual(DisplayMode.mirror.wireValue, "mirror")
        XCTAssertEqual(DisplayMode(wireValue: "mirror"), .mirror)
        XCTAssertEqual(DisplayMode(wireValue: "extend"), .extend)
        XCTAssertNil(DisplayMode(wireValue: "other"))
    }
}
