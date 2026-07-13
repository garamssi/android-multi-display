import Foundation

/// One parsed line of the DeskLink unified log, split for color-tagged rendering in the
/// diagnostics console: a short `timestamp` ("HH:MM:SS.mmm"), the emitting `category`
/// (server / stream / capture / adb), and the human `message`. `timestamp` and `category`
/// are nil for lines that don't match the log format (e.g. a `log show` preamble); those
/// render as plain body text.
public struct DiagnosticLogLine: Equatable, Identifiable {
    public let id: Int
    public let timestamp: String?
    public let category: String?
    public let message: String

    public init(id: Int, timestamp: String?, category: String?, message: String) {
        self.id = id
        self.timestamp = timestamp
        self.category = category
        self.message = message
    }
}

/// Pure formatter turning the raw `log show --style compact` output for our subsystem into
/// color-taggable lines. Kept free of SwiftUI so it is unit-testable; the view maps each
/// `category` to a token color. It anchors only on the stable parts of the compact format
/// — the leading timestamp and the `[subsystem:category]` tag — so the variable middle
/// columns (thread, type, pid) don't affect parsing.
enum DiagnosticLogParser {

    /// Splits `text` into non-empty lines and parses each. Blank lines are dropped; input
    /// order is preserved and each kept line gets a sequential, stable `id`.
    static func parse(_ text: String) -> [DiagnosticLogLine] {
        var result: [DiagnosticLogLine] = []
        for raw in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = String(raw)
            if line.trimmingCharacters(in: .whitespaces).isEmpty { continue }
            result.append(parseLine(line, id: result.count))
        }
        return result
    }

    // MARK: - Private

    // These patterns are compile-time constants over a fixed format, so construction
    // cannot fail; `try!` is the correct expression of "this literal is always valid"
    // rather than a nil-dodging force-unwrap.
    /// Leading `YYYY-MM-DD HH:MM:SS.ffffff` -> captures "HH:MM:SS.mmm" (ms cut to 3 digits).
    private static let timestampRegex = try! NSRegularExpression(
        pattern: #"^\d{4}-\d{2}-\d{2} (\d{2}:\d{2}:\d{2}\.\d{3})"#
    )

    /// `[<subsystem>:<category>]` -> captures the category word. Built from `Log.subsystem`
    /// so it tracks the real subsystem instead of duplicating the literal.
    private static let categoryRegex = try! NSRegularExpression(
        pattern: "\\[" + NSRegularExpression.escapedPattern(for: Log.subsystem) + ":(\\w+)\\]"
    )

    private static func parseLine(_ line: String, id: Int) -> DiagnosticLogLine {
        let full = NSRange(line.startIndex..<line.endIndex, in: line)
        let timestamp = firstGroup(timestampRegex, in: line, range: full)

        guard let tagMatch = categoryRegex.firstMatch(in: line, options: [], range: full),
              let categoryRange = Range(tagMatch.range(at: 1), in: line),
              let tagRange = Range(tagMatch.range, in: line) else {
            // No category tag: the whole trimmed line is the message.
            return DiagnosticLogLine(
                id: id, timestamp: timestamp, category: nil,
                message: line.trimmingCharacters(in: .whitespaces)
            )
        }

        let category = String(line[categoryRange])
        let message = String(line[tagRange.upperBound...]).trimmingCharacters(in: .whitespaces)
        return DiagnosticLogLine(id: id, timestamp: timestamp, category: category, message: message)
    }

    private static func firstGroup(
        _ regex: NSRegularExpression, in string: String, range: NSRange
    ) -> String? {
        guard let match = regex.firstMatch(in: string, options: [], range: range),
              let group = Range(match.range(at: 1), in: string) else { return nil }
        return String(string[group])
    }
}
