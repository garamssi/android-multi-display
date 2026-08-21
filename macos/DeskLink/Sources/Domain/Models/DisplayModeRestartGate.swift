import Foundation

/// Serializes the session restarts that a display-mode change triggers.
///
/// Changing the mode changes what is captured and at what size, and the client learns both
/// only from the handshake, so the session has to be rebuilt. Two quick toggles would
/// otherwise run overlapping stop/start sequences over one set of ports — the second tearing
/// down what the first is still binding.
///
/// At most one mode is queued behind the running restart. Intermediate modes are irrelevant:
/// only the one the user settled on has to be served.
struct DisplayModeRestartGate {

    private(set) var isRestarting = false

    /// The mode the in-flight restart is bringing up.
    private var inFlight: DisplayMode?

    private var queued: DisplayMode?

    /// Returns the mode to restart into now, or nil when the request was queued or dropped.
    ///
    /// `current` is the mode the running session was started in. It is only consulted when no
    /// restart is in flight: a session being restarted has not applied its new mode yet, so
    /// comparing against `current` there would drop a toggle back to the original mode and
    /// leave the user on a mode they did not pick.
    mutating func request(_ mode: DisplayMode, current: DisplayMode) -> DisplayMode? {
        let pending = inFlight ?? current
        guard mode != pending else {
            // Already running or already coming up; nothing to do either way.
            queued = nil
            return nil
        }
        guard isRestarting else {
            isRestarting = true
            inFlight = mode
            return mode
        }
        queued = mode
        return nil
    }

    /// Call when a restart completes. Returns the queued mode, which is now in flight.
    mutating func finish() -> DisplayMode? {
        guard let next = queued else {
            isRestarting = false
            inFlight = nil
            return nil
        }
        queued = nil
        inFlight = next
        return next
    }
}
