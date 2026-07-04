import XCTest
@testable import DeskLink

final class ConnectionMonitorTests: XCTestCase {

    /// A fresh monitor is not timed out.
    func testInitiallyAlive() {
        let clock = VirtualClock(startMillis: 0)
        let monitor = ConnectionMonitor(clock: clock, pingIntervalMs: 1000, pingTimeoutMs: 3000)
        XCTAssertFalse(monitor.isTimedOut())
    }

    /// No PONG for > PING_TIMEOUT → disconnected (deterministic, no sleeping).
    func testTimesOutAfterTimeoutWithoutPong() {
        let clock = VirtualClock(startMillis: 0)
        let monitor = ConnectionMonitor(clock: clock, pingIntervalMs: 1000, pingTimeoutMs: 3000)

        clock.advance(by: 3000)
        XCTAssertFalse(monitor.isTimedOut(), "Exactly at timeout is still alive (strictly greater)")

        clock.advance(by: 1)
        XCTAssertTrue(monitor.isTimedOut(), "3001ms without a PONG is a disconnect")
    }

    /// A PONG resets the timeout window.
    func testPongResetsTimeout() {
        let clock = VirtualClock(startMillis: 0)
        let monitor = ConnectionMonitor(clock: clock, pingIntervalMs: 1000, pingTimeoutMs: 3000)

        clock.advance(by: 2500)
        XCTAssertFalse(monitor.isTimedOut())
        monitor.recordPong() // at t=2500

        clock.advance(by: 2500) // t=5000, only 2500ms since last PONG
        XCTAssertFalse(monitor.isTimedOut())

        clock.advance(by: 600) // 3100ms since last PONG
        XCTAssertTrue(monitor.isTimedOut())
    }

    /// PING is sent at most once per interval.
    func testShouldSendPingRespectsInterval() {
        let clock = VirtualClock(startMillis: 0)
        let monitor = ConnectionMonitor(clock: clock, pingIntervalMs: 1000, pingTimeoutMs: 3000)

        // At construction the first ping is allowed immediately.
        XCTAssertTrue(monitor.shouldSendPing())
        // Immediately after, no second ping until the interval elapses.
        XCTAssertFalse(monitor.shouldSendPing())

        clock.advance(by: 999)
        XCTAssertFalse(monitor.shouldSendPing())

        clock.advance(by: 1)   // now exactly 1000ms since last ping
        XCTAssertTrue(monitor.shouldSendPing())
        XCTAssertFalse(monitor.shouldSendPing())
    }

    /// Three missed PINGs (3s) is the documented disconnect threshold.
    func testThreeMissedPingsEqualsTimeout() {
        let clock = VirtualClock(startMillis: 0)
        let monitor = ConnectionMonitor(
            clock: clock,
            pingIntervalMs: Int64(ProtocolConstants.pingInterval),
            pingTimeoutMs: Int64(ProtocolConstants.pingTimeout)
        )
        // Simulate 3 ping intervals passing with no PONG.
        clock.advance(by: Int64(ProtocolConstants.pingInterval) * 3)
        XCTAssertFalse(monitor.isTimedOut()) // exactly 3000ms → still alive
        clock.advance(by: 1)
        XCTAssertTrue(monitor.isTimedOut())
    }

    func testMillisSinceLastPong() {
        let clock = VirtualClock(startMillis: 1000)
        let monitor = ConnectionMonitor(clock: clock, pingIntervalMs: 1000, pingTimeoutMs: 3000)
        clock.advance(by: 1500)
        XCTAssertEqual(monitor.millisSinceLastPong(), 1500)
    }
}
