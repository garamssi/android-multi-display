import Foundation
import os

/// Unified-logging facade for the whole app. Replaces scattered `NSLog` calls.
///
/// Why os.Logger over NSLog: `Logger` writes to the unified log with a real
/// subsystem/category (filterable in Console / `log`), and — critically — lets us
/// mark the message `privacy: .public` so diagnostic lines are NOT redacted to
/// `<private>` the way `NSLog`'s interpolated strings were. Our messages are our own
/// operational text (no user PII), so publishing them is intentional.
///
/// `debug(...)` is gated behind [isVerbose] (a persisted "Diagnostic logging" toggle)
/// so high-frequency per-frame lines don't flood the log unless the user opts in.
///
/// Thread-safety: the per-category `Logger`s are immutable (`Logger` is `Sendable`)
/// and the verbose flag lives in `UserDefaults`, which is itself thread-safe — so this
/// facade is safe to call from any actor/thread with no extra synchronization.
enum Log {

    /// Unified-log subsystem. Filter with `log stream --predicate 'subsystem == "com.desklink.mac"'`.
    static let subsystem = "com.desklink.mac"

    enum Category: String {
        case server
        case stream
        case capture
        case adb
    }

    /// Key for the persisted "Diagnostic logging (verbose)" preference.
    static let verboseDefaultsKey = "diagnosticVerbose"

    /// When true, [debug] lines are emitted (verbose diagnostic mode).
    static var isVerbose: Bool {
        get { UserDefaults.standard.bool(forKey: verboseDefaultsKey) }
        set { UserDefaults.standard.set(newValue, forKey: verboseDefaultsKey) }
    }

    /// Always logged at the default level, with the message published (not redacted).
    static func info(_ category: Category, _ message: @autoclosure () -> String) {
        // Evaluate the autoclosure into a value first: os.Logger's string interpolation
        // takes an @escaping autoclosure, so passing our non-escaping `message` directly
        // would "escape" it. Capturing a plain String is fine.
        let text = message()
        logger(for: category).log("\(text, privacy: .public)")
    }

    /// Logged at the error level (always emitted).
    static func error(_ category: Category, _ message: @autoclosure () -> String) {
        let text = message()
        logger(for: category).error("\(text, privacy: .public)")
    }

    /// Emitted ONLY when [isVerbose] is on. Use for high-frequency lines (per-frame
    /// capture/stream traces). The `@autoclosure` means the string isn't built when
    /// verbose is off, so the gate is genuinely cheap.
    static func debug(_ category: Category, _ message: @autoclosure () -> String) {
        guard isVerbose else { return }
        let text = message()
        logger(for: category).log("\(text, privacy: .public)")
    }

    // MARK: - Private

    private static let loggers: [Category: Logger] = [
        .server: Logger(subsystem: subsystem, category: Category.server.rawValue),
        .stream: Logger(subsystem: subsystem, category: Category.stream.rawValue),
        .capture: Logger(subsystem: subsystem, category: Category.capture.rawValue),
        .adb: Logger(subsystem: subsystem, category: Category.adb.rawValue),
    ]

    private static func logger(for category: Category) -> Logger {
        loggers[category] ?? Logger(subsystem: subsystem, category: category.rawValue)
    }
}
