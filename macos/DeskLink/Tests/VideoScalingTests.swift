import XCTest
@testable import DeskLink

// Mirror sends the Mac's own screen, whose shape has nothing to do with the tablet's. Fit
// keeps every pixel and pays with bars; Fill uses the whole panel and pays by cropping. There
// is no third answer, so the user picks which price.
final class VideoScalingTests: XCTestCase {

    func testWireValuesAreStable() {
        XCTAssertEqual(VideoScaling.fit.wireValue, "fit")
        XCTAssertEqual(VideoScaling.fill.wireValue, "fill")
    }

    func testWireValuesRoundTrip() {
        for scaling in VideoScaling.allCases {
            XCTAssertEqual(VideoScaling(wireValue: scaling.wireValue), scaling)
        }
    }

    // An older or newer server may name something this client does not know. Showing the
    // whole picture is the answer that loses nothing.
    func testUnknownWireValueIsRejectedSoTheCallerCanDefaultToFit() {
        XCTAssertNil(VideoScaling(wireValue: "cover"))
        XCTAssertNil(VideoScaling(wireValue: ""))
    }

    func testDefaultKeepsTheWholePicture() {
        XCTAssertEqual(VideoScalingPreference.defaultScaling, .fit)
    }

    func testPreferencePersistsAndDefaultsToFit() {
        let suiteName = "com.desklink.tests.scaling.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let preference = VideoScalingPreference(defaults: defaults)
        XCTAssertEqual(preference.scaling, .fit)

        preference.scaling = .fill
        XCTAssertEqual(VideoScalingPreference(defaults: defaults).scaling, .fill)
    }

    func testAGarbledStoredValueFallsBackToFit() {
        let suiteName = "com.desklink.tests.scaling.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.set("stretch", forKey: VideoScalingPreference.defaultsKey)

        XCTAssertEqual(VideoScalingPreference(defaults: defaults).scaling, .fit)
    }

    func testChangesAreObservable() async {
        let suiteName = "com.desklink.tests.scaling.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let preference = VideoScalingPreference(defaults: defaults)

        var iterator = VideoScalingPreference.scalingChanges(defaults: defaults).makeAsyncIterator()
        preference.scaling = .fill

        let observed = await iterator.next()
        XCTAssertEqual(observed, .fill)
    }
}
