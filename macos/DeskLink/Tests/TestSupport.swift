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
