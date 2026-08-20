import Foundation

public enum ControlMessage {

    public static func timestampPayload(millis: Int64) -> Data {
        var data = Data(capacity: 8)
        withUnsafeBytes(of: UInt64(bitPattern: millis).bigEndian) { data.append(contentsOf: $0) }
        return data
    }

    public static func parseTimestamp(_ data: Data) -> Int64? {
        guard data.count == 8 else { return nil }
        return data.withUnsafeBytes { raw in
            Int64(bitPattern: UInt64(bigEndian: raw.loadUnaligned(fromByteOffset: 0, as: UInt64.self)))
        }
    }
}
