import XCTest
@testable import DeskLink

/// The "route audio to the tablet" preference. While it is on and a client is
/// connected, the Mac's own speakers are silent — so the user must be able to turn it
/// off, and it must default to OFF so enabling screen mirroring never silences the Mac
/// without the user asking for it.
final class AudioOutputPreferenceTests: XCTestCase {

    private var suiteName = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "AudioOutputPreferenceTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    /// Defaults to off: mirroring the screen must not silently take over audio.
    func testDefaultsToDisabled() {
        XCTAssertFalse(AudioOutputPreference(defaults: defaults).routeToTablet)
    }

    func testPersistsEnabledState() {
        AudioOutputPreference(defaults: defaults).routeToTablet = true
        XCTAssertTrue(AudioOutputPreference(defaults: defaults).routeToTablet)
    }

    func testPersistsDisabledState() {
        let preference = AudioOutputPreference(defaults: defaults)
        preference.routeToTablet = true
        preference.routeToTablet = false
        XCTAssertFalse(AudioOutputPreference(defaults: defaults).routeToTablet)
    }

    /// The key is part of the on-disk contract; renaming it silently resets everyone's
    /// preference, so it is pinned.
    func testDefaultsKeyIsStable() {
        XCTAssertEqual(AudioOutputPreference.defaultsKey, "audioRouteToTablet")
    }
}
