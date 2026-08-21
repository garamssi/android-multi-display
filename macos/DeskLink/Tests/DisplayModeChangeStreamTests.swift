import XCTest
@testable import DeskLink

// Reading the mode only at start() meant the user had to hand-time Stop/Start on the Mac
// within the client's reconnect budget. These cover the stream that replaces that.
final class DisplayModeChangeStreamTests: XCTestCase {

    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "com.desklink.tests.displaymode.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    func testEmitsTheNewModeWhenItChanges() async {
        let preference = DisplayModePreference(defaults: defaults)
        preference.mode = .extend

        var iterator = DisplayModePreference.modeChanges(defaults: defaults).makeAsyncIterator()

        // AsyncStream buffers, so writing before the await is deterministic; a Task here
        // would only add a race to the test.
        preference.mode = .mirror

        let observed = await iterator.next()
        XCTAssertEqual(observed, .mirror)
    }

    // UserDefaults.didChangeNotification fires for every key in the suite, so an unrelated
    // write must not look like a mode change and restart the session.
    func testDoesNotEmitWhenTheModeIsUnchanged() async {
        let preference = DisplayModePreference(defaults: defaults)
        preference.mode = .mirror

        var iterator = DisplayModePreference.modeChanges(defaults: defaults).makeAsyncIterator()

        defaults.set(true, forKey: "somethingElse")
        defaults.set("mirror", forKey: DisplayModePreference.defaultsKey)
        // Only this one is a real change, so it is what must arrive.
        preference.mode = .extend

        let observed = await iterator.next()
        XCTAssertEqual(observed, .extend)
    }
}
