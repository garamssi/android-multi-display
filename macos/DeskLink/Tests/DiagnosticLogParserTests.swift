import XCTest
@testable import DeskLink

final class DiagnosticLogParserTests: XCTestCase {

    /// A representative `log show --style compact` line: leading date+time, some columns,
    /// then the `[subsystem:category]` tag and the message. The parser must anchor only on
    /// the timestamp prefix and the tag, ignoring the variable middle columns.
    private func compactLine(_ category: String, _ message: String,
                             time: String = "09:30:20.082548+0900") -> String {
        "2026-07-10 \(time) 0x1a2b     Df  0x0    1234   0    "
            + "DeskLink: [\(Log.subsystem):\(category)] \(message)"
    }

    func testParsesTimestampCategoryAndMessage() {
        let line = compactLine("server", "starting servers scope=loopback+LAN")

        let parsed = DiagnosticLogParser.parse(line)

        XCTAssertEqual(parsed.count, 1)
        XCTAssertEqual(parsed[0].timestamp, "09:30:20.082")   // ms truncated to 3 digits
        XCTAssertEqual(parsed[0].category, "server")
        XCTAssertEqual(parsed[0].message, "starting servers scope=loopback+LAN")
    }

    func testRecognizesEachCategory() {
        for category in ["server", "stream", "capture", "adb"] {
            let parsed = DiagnosticLogParser.parse(compactLine(category, "hello"))
            XCTAssertEqual(parsed.first?.category, category)
        }
    }

    func testNonMatchingLineBecomesPlainMessage() {
        let line = "Filtering the log data using \"subsystem == \\\"com.desklink.mac\\\"\""

        let parsed = DiagnosticLogParser.parse(line)

        XCTAssertEqual(parsed.count, 1)
        XCTAssertNil(parsed[0].timestamp)
        XCTAssertNil(parsed[0].category)
        XCTAssertEqual(parsed[0].message, line)
    }

    func testDropsBlankLinesAndPreservesOrderWithStableIds() {
        let text = [
            compactLine("server", "one"),
            "",
            "   ",
            compactLine("stream", "two"),
        ].joined(separator: "\n")

        let parsed = DiagnosticLogParser.parse(text)

        XCTAssertEqual(parsed.map(\.message), ["one", "two"])
        XCTAssertEqual(parsed.map(\.category), ["server", "stream"])
        XCTAssertEqual(parsed.map(\.id), [0, 1], "ids are sequential over kept lines")
    }

    func testMessageWithBracketsIsNotTruncated() {
        let line = compactLine("stream", "sent VIDEO_CONFIG [79 bytes]")

        let parsed = DiagnosticLogParser.parse(line)

        XCTAssertEqual(parsed.first?.message, "sent VIDEO_CONFIG [79 bytes]")
    }
}
