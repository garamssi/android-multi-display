import Foundation

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

enum DiagnosticLogParser {

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

    // try! is correct: these are compile-time-constant regex patterns that are always valid, not a nil-dodging force-unwrap.
    private static let timestampRegex = try! NSRegularExpression(
        pattern: #"^\d{4}-\d{2}-\d{2} (\d{2}:\d{2}:\d{2}\.\d{3})"#
    )

    private static let categoryRegex = try! NSRegularExpression(
        pattern: "\\[" + NSRegularExpression.escapedPattern(for: Log.subsystem) + ":(\\w+)\\]"
    )

    private static func parseLine(_ line: String, id: Int) -> DiagnosticLogLine {
        let full = NSRange(line.startIndex..<line.endIndex, in: line)
        let timestamp = firstGroup(timestampRegex, in: line, range: full)

        guard let tagMatch = categoryRegex.firstMatch(in: line, options: [], range: full),
              let categoryRange = Range(tagMatch.range(at: 1), in: line),
              let tagRange = Range(tagMatch.range, in: line) else {
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
