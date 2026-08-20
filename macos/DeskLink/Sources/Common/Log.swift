import Foundation
import os

enum Log {

    static let subsystem = "com.desklink.mac"

    enum Category: String {
        case server
        case stream
        case capture
        case adb
    }

    static let verboseDefaultsKey = "diagnosticVerbose"

    static var isVerbose: Bool {
        get { UserDefaults.standard.bool(forKey: verboseDefaultsKey) }
        set { UserDefaults.standard.set(newValue, forKey: verboseDefaultsKey) }
    }

    static func info(_ category: Category, _ message: @autoclosure () -> String) {
        // Capture into a value first: Logger's interpolation takes an @escaping autoclosure, so passing the non-escaping message directly would escape it.
        let text = message()
        logger(for: category).log("\(text, privacy: .public)")
    }

    static func error(_ category: Category, _ message: @autoclosure () -> String) {
        let text = message()
        logger(for: category).error("\(text, privacy: .public)")
    }

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
