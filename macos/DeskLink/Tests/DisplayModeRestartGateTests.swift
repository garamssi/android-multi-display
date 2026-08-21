import XCTest
@testable import DeskLink

// The gate exists because two quick toggles must not run overlapping stop/start sequences
// over one set of ports.
final class DisplayModeRestartGateTests: XCTestCase {

    func testFirstRequestIsAdmittedImmediately() {
        var gate = DisplayModeRestartGate()
        XCTAssertEqual(gate.request(.mirror, current: .extend), .mirror)
        XCTAssertTrue(gate.isRestarting)
    }

    func testSecondRequestWhileRestartingIsNotAdmitted() {
        var gate = DisplayModeRestartGate()
        _ = gate.request(.mirror, current: .extend)
        XCTAssertNil(gate.request(.extend, current: .extend), "a restart is in flight; admitting a second one would tear down the ports it is rebinding")
    }

    func testQueuedRequestRunsWhenTheRestartFinishes() {
        var gate = DisplayModeRestartGate()
        _ = gate.request(.mirror, current: .extend)
        _ = gate.request(.extend, current: .extend)
        XCTAssertEqual(gate.finish(), .extend)
        XCTAssertTrue(gate.isRestarting, "the queued restart is now the one in flight")
    }

    // Only the mode the user settled on has to be served. With two modes, "keep the latest
    // request" and "drop a request for what is already coming up" are the same rule, which
    // is why the queue holds one entry rather than a list.
    func testRequestingTheInFlightModeAgainIsDropped() {
        var gate = DisplayModeRestartGate()
        _ = gate.request(.mirror, current: .extend)
        XCTAssertNil(gate.request(.mirror, current: .extend))
        XCTAssertNil(gate.finish(), "nothing was queued, so there is no second restart")
    }

    func testFinishWithNothingQueuedClearsTheGate() {
        var gate = DisplayModeRestartGate()
        _ = gate.request(.mirror, current: .extend)
        XCTAssertNil(gate.finish())
        XCTAssertFalse(gate.isRestarting)
    }

    // Toggling to a mode and back while the first restart runs leaves nothing to do: the
    // session being brought up is already the one the user wants.
    func testQueueingTheModeAlreadyBeingStartedIsDropped() {
        var gate = DisplayModeRestartGate()
        _ = gate.request(.mirror, current: .extend)
        _ = gate.request(.extend, current: .extend)
        _ = gate.request(.mirror, current: .extend)
        XCTAssertNil(gate.finish(), "the in-flight restart already targets mirror")
        XCTAssertFalse(gate.isRestarting)
    }

    // The session being restarted has not applied the new mode yet, so "what is running now"
    // is the wrong thing to compare against: a user who toggles away and back mid-restart
    // must still end up on the mode they picked last.
    func testTogglingBackMidRestartQueuesTheOriginalMode() {
        var gate = DisplayModeRestartGate()
        _ = gate.request(.mirror, current: .extend)
        XCTAssertNil(gate.request(.extend, current: .extend), "queued, not run: a restart is in flight")
        XCTAssertEqual(gate.finish(), .extend)
    }

    func testRequestingWhatIsAlreadyRunningDoesNothing() {
        var gate = DisplayModeRestartGate()
        XCTAssertNil(gate.request(.extend, current: .extend))
        XCTAssertFalse(gate.isRestarting)
    }

    // The caller passes the mode the session is actually running, so after a restart into
    // mirror that is what `current` is.
    func testGateIsReusableAfterFinishing() {
        var gate = DisplayModeRestartGate()
        _ = gate.request(.mirror, current: .extend)
        _ = gate.finish()
        XCTAssertEqual(gate.request(.extend, current: .mirror), .extend)
    }
}
