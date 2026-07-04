import Foundation

/// Abstraction of a monotonic clock so liveness logic can be tested with a
/// virtual clock instead of real time.
public protocol MonotonicClock: Sendable {
    /// Current time in milliseconds (monotonic; only differences are meaningful).
    func nowMillis() -> Int64
}

/// Real wall-clock implementation.
public struct SystemMonotonicClock: MonotonicClock {
    public init() {}
    public func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

/// A settable clock for deterministic tests.
public final class VirtualClock: MonotonicClock, @unchecked Sendable {
    private let lock = NSLock()
    private var millis: Int64

    public init(startMillis: Int64 = 0) {
        self.millis = startMillis
    }

    public func nowMillis() -> Int64 {
        lock.withLock { millis }
    }

    /// Advances the virtual clock by the given number of milliseconds.
    public func advance(by ms: Int64) {
        lock.withLock { millis += ms }
    }
}

/// Pure liveness state machine for the control channel (S-L10).
///
/// Responsibilities (kept free of I/O so they can be unit-tested with `VirtualClock`):
/// - Decide when a PING should be sent (`shouldSendPing`), every `pingIntervalMs`.
/// - Record PONG receipt (`recordPong`).
/// - Report disconnection when no PONG has arrived within `pingTimeoutMs`.
///
/// Timings default to `ProtocolConstants` (PING_INTERVAL 1000ms, PING_TIMEOUT 3000ms).
public final class ConnectionMonitor: @unchecked Sendable {
    private let clock: MonotonicClock
    private let pingIntervalMs: Int64
    private let pingTimeoutMs: Int64
    private let lock = NSLock()

    private var lastPongMs: Int64
    private var lastPingSentMs: Int64

    public init(
        clock: MonotonicClock = SystemMonotonicClock(),
        pingIntervalMs: Int64 = Int64(ProtocolConstants.pingInterval),
        pingTimeoutMs: Int64 = Int64(ProtocolConstants.pingTimeout)
    ) {
        self.clock = clock
        self.pingIntervalMs = pingIntervalMs
        self.pingTimeoutMs = pingTimeoutMs
        let now = clock.nowMillis()
        // Treat the connection as fresh at construction: a PONG is considered
        // just-received so the timeout window starts now.
        self.lastPongMs = now
        self.lastPingSentMs = now - pingIntervalMs // allow an immediate first ping
    }

    /// Records that a PONG was received (call when a PONG frame arrives).
    public func recordPong() {
        lock.withLock { lastPongMs = clock.nowMillis() }
    }

    /// Returns true if it is time to send another PING (interval elapsed).
    /// When it returns true it also records the send time, so callers should send
    /// exactly one PING per `true` result.
    public func shouldSendPing() -> Bool {
        lock.withLock {
            let now = clock.nowMillis()
            guard now - lastPingSentMs >= pingIntervalMs else { return false }
            lastPingSentMs = now
            return true
        }
    }

    /// Returns true if the peer is considered disconnected: no PONG within the
    /// timeout window.
    public func isTimedOut() -> Bool {
        lock.withLock {
            clock.nowMillis() - lastPongMs > pingTimeoutMs
        }
    }

    /// Milliseconds since the last PONG (for diagnostics/tests).
    public func millisSinceLastPong() -> Int64 {
        lock.withLock { clock.nowMillis() - lastPongMs }
    }
}
