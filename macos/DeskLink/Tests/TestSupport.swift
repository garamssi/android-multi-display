import Foundation

/// Test-only helpers for asserting protocol golden vectors as hex strings.
extension Data {
    /// Uppercase hex representation with no separators (e.g. "0A1BFF").
    var hexString: String {
        map { String(format: "%02X", $0) }.joined()
    }

    /// Builds `Data` from an uppercase/lowercase hex string (ignores spaces).
    init(hex: String) {
        let cleaned = hex.replacingOccurrences(of: " ", with: "")
        var bytes = [UInt8]()
        bytes.reserveCapacity(cleaned.count / 2)
        var index = cleaned.startIndex
        while index < cleaned.endIndex {
            let next = cleaned.index(index, offsetBy: 2)
            bytes.append(UInt8(cleaned[index..<next], radix: 16)!)
            index = next
        }
        self.init(bytes)
    }
}

/// A clock the tests move by hand, so a lockout window is verified without sleeping.
final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(start: Date = Date(timeIntervalSince1970: 1_700_000_000)) {
        current = start
    }

    var now: @Sendable () -> Date {
        { [self] in lock.withLock { current } }
    }

    func advance(by seconds: TimeInterval) {
        lock.withLock { current = current.addingTimeInterval(seconds) }
    }
}
