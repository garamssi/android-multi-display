import Foundation

/// Serialization helpers for the fixed-layout control-channel messages
/// (PING / PONG). Both carry an 8-byte Int64 timestamp in **milliseconds**
/// (Unix epoch), Big-Endian, per protocol spec §3.7.
public enum ControlMessage {

    /// Serializes a PING/PONG payload: Int64 milliseconds, Big-Endian (8 bytes).
    public static func timestampPayload(millis: Int64) -> Data {
        var data = Data(capacity: 8)
        withUnsafeBytes(of: UInt64(bitPattern: millis).bigEndian) { data.append(contentsOf: $0) }
        return data
    }

    /// Parses a PING/PONG payload back into milliseconds. Returns nil if the
    /// payload is not exactly 8 bytes.
    public static func parseTimestamp(_ data: Data) -> Int64? {
        guard data.count == 8 else { return nil }
        return data.withUnsafeBytes { raw in
            Int64(bitPattern: UInt64(bigEndian: raw.loadUnaligned(fromByteOffset: 0, as: UInt64.self)))
        }
    }
}
