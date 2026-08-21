import Foundation

/// Emits a preference's value whenever it actually changes, so a running server can follow
/// the setting instead of only reading it at start.
///
/// `UserDefaults.didChangeNotification` fires for any key in the suite, so the value is
/// de-duplicated here. Without that, every unrelated defaults write looks like a change and
/// the server acts on it — rebuilding the audio tap, or restarting the whole session.
///
/// Shared by every preference that needs this: duplicating the observer bookkeeping per
/// preference is how one of them ends up leaking its observer token.
enum DefaultsChangeStream {

    static func make<Value: Equatable & Sendable>(
        defaults: UserDefaults,
        read: @escaping @Sendable (UserDefaults) -> Value
    ) -> AsyncStream<Value> {
        AsyncStream { continuation in
            let state = ChangeObserverState<Value>(initial: read(defaults))

            let observer = NotificationCenter.default.addObserver(
                forName: UserDefaults.didChangeNotification,
                object: defaults,
                queue: nil
            ) { _ in
                let current = read(defaults)
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

/// Holds the de-duplication state and the observer token for one stream.
///
/// The notification callback and the stream's termination handler run in different
/// contexts, so the shared state is behind a lock rather than captured as a `var`.
private final class ChangeObserverState<Value: Equatable & Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var last: Value
    private var observer: NSObjectProtocol?

    init(initial: Value) {
        last = initial
    }

    /// Records `value` and reports whether it differs from the previous one.
    func updateIfChanged(to value: Value) -> Bool {
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
