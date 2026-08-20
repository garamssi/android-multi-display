import Foundation

public protocol MonotonicClock: Sendable {
    func nowMillis() -> Int64
}

public struct SystemMonotonicClock: MonotonicClock {
    public init() {}
    public func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

public final class VirtualClock: MonotonicClock, @unchecked Sendable {
    private let lock = NSLock()
    private var millis: Int64

    public init(startMillis: Int64 = 0) {
        self.millis = startMillis
    }

    public func nowMillis() -> Int64 {
        lock.withLock { millis }
    }

    public func advance(by ms: Int64) {
        lock.withLock { millis += ms }
    }
}

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
        self.lastPongMs = now
        self.lastPingSentMs = now - pingIntervalMs // allow an immediate first ping
    }

    public func recordPong() {
        lock.withLock { lastPongMs = clock.nowMillis() }
    }

    // Side effect: a true result also records the send time, so call once per true and send exactly one PING.
    public func shouldSendPing() -> Bool {
        lock.withLock {
            let now = clock.nowMillis()
            guard now - lastPingSentMs >= pingIntervalMs else { return false }
            lastPingSentMs = now
            return true
        }
    }

    public func isTimedOut() -> Bool {
        lock.withLock {
            clock.nowMillis() - lastPongMs > pingTimeoutMs
        }
    }

    public func millisSinceLastPong() -> Int64 {
        lock.withLock { clock.nowMillis() - lastPongMs }
    }
}
