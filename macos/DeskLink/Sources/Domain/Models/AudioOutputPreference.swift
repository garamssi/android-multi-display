import Foundation

/// The user's "play Mac audio on the tablet" choice.
///
/// Defaults to OFF deliberately. While audio is routed to the tablet the Mac's own
/// output is suppressed, and having that happen merely because the user started screen
/// mirroring would be a surprising loss of sound. Turning it off releases the tap, and
/// the speakers come back — see `routeToTabletChanges`, which is what makes that take
/// effect on a running server rather than at the next start.
/// `@unchecked Sendable` is the correct model here rather than a silenced warning:
/// `UserDefaults` is documented as thread-safe and is the only stored state, so no
/// additional synchronization exists to get wrong. `Log` relies on the same property.
public final class AudioOutputPreference: @unchecked Sendable {

    public static let defaultsKey = "audioRouteToTablet"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// When true, system audio is captured and sent to the connected tablet instead of
    /// playing on the Mac.
    public var routeToTablet: Bool {
        get { defaults.bool(forKey: Self.defaultsKey) }
        set { defaults.set(newValue, forKey: Self.defaultsKey) }
    }

    /// Emits the value whenever it changes, so a running server can follow the preference
    /// instead of only reading it at start.
    ///
    /// Driven by `UserDefaults.didChangeNotification`, which fires for any key, so the
    /// value is de-duplicated here — a server acting on every unrelated defaults write
    /// would tear the tap down and rebuild it for no reason.
    public static var routeToTabletChanges: AsyncStream<Bool> {
        AsyncStream { continuation in
            let state = ChangeObserverState()
            let preference = AudioOutputPreference()
            state.setLast(preference.routeToTablet)

            let observer = NotificationCenter.default.addObserver(
                forName: UserDefaults.didChangeNotification,
                object: UserDefaults.standard,
                queue: nil
            ) { _ in
                let current = preference.routeToTablet
                guard state.updateIfChanged(to: current) else { return }
                continuation.yield(current)
            }
            state.setObserver(observer)

            continuation.onTermination = { _ in
                state.removeObserver()
            }
        }
    }
}

/// Holds the de-duplication state and the observer token for `routeToTabletChanges`.
///
/// The notification callback and the stream's termination handler run in different
/// contexts, so the shared state is behind a lock rather than captured as a `var`.
private final class ChangeObserverState: @unchecked Sendable {
    private let lock = NSLock()
    private var last = false
    private var observer: NSObjectProtocol?

    func setLast(_ value: Bool) {
        lock.withLock { last = value }
    }

    /// Records `value` and reports whether it differs from the previous one.
    func updateIfChanged(to value: Bool) -> Bool {
        lock.withLock {
            guard value != last else { return false }
            last = value
            return true
        }
    }

    func setObserver(_ observer: NSObjectProtocol) {
        lock.withLock { self.observer = observer }
    }

    func removeObserver() {
        let token = lock.withLock { () -> NSObjectProtocol? in
            let current = observer
            observer = nil
            return current
        }
        if let token { NotificationCenter.default.removeObserver(token) }
    }
}
