import Foundation

/// Everything about a session that the client learns only from the handshake.
///
/// Grouped because they share one consequence: changing either means a new session, so one
/// gate arbitrates both rather than each growing its own.
struct SessionSettings: Equatable {
    let mode: DisplayMode
    let scaling: VideoScaling
}

/// Serializes the session restarts that a change of display settings triggers.
///
/// The mode changes what is captured and at what size; fit/fill changes how the tablet fits it
/// to its panel. The client learns both only from the handshake, so the session has to be
/// rebuilt either way. Two quick toggles would
/// otherwise run overlapping stop/start sequences over one set of ports — the second tearing
/// down what the first is still binding.
///
/// At most one is queued behind the running restart. Intermediate choices are irrelevant:
/// only what the user settled on has to be served.
struct SessionRestartGate {

    private(set) var isRestarting = false

    /// The session the in-flight restart is bringing up.
    private var inFlight: SessionSettings?

    private var queued: SessionSettings?

    /// Returns the settings to restart into now, or nil when the request was queued or dropped.
    ///
    /// `current` is what the running session was started with. It is only consulted when no
    /// restart is in flight: a session being restarted has not applied its new mode yet, so
    /// comparing against `current` there would drop a toggle back to the original settings and
    /// leave the user on settings they did not pick.
    mutating func request(_ settings: SessionSettings, current: SessionSettings) -> SessionSettings? {
        let pending = inFlight ?? current
        guard settings != pending else {
            // Already running or already coming up; nothing to do either way.
            queued = nil
            return nil
        }
        guard isRestarting else {
            isRestarting = true
            inFlight = settings
            return settings
        }
        queued = settings
        return nil
    }

    /// Call when a restart completes. Returns the queued settings, which are now in flight.
    mutating func finish() -> SessionSettings? {
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
