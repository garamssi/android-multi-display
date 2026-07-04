import Foundation

/// Accumulates raw bytes received from a stream socket and yields complete
/// protocol frames as they become available. TCP is a byte stream, so a single
/// `recv` may contain a partial frame, exactly one frame, or several frames.
///
/// This type is pure (no I/O) and fully unit-testable: feed it arbitrary byte
/// chunks and it returns whatever complete `(type, payload)` frames can be parsed,
/// retaining any trailing partial bytes for the next call.
public struct FrameAccumulator {
    private var buffer = Data()

    public init() {}

    /// A parsed frame.
    public struct Frame: Equatable {
        public let type: MessageType
        public let payload: Data
    }

    public enum AccumulateError: Error, Equatable {
        case protocolError(String)
    }

    /// Appends newly received bytes and returns all complete frames now available.
    /// - Throws: `AccumulateError.protocolError` on an unrecoverable framing error
    ///   (unknown type, oversized packet); the buffer is left intact for inspection.
    public mutating func append(_ data: Data) throws -> [Frame] {
        buffer.append(data)
        var frames: [Frame] = []

        while true {
            switch PacketFramer.unframe(buffer: buffer) {
            case .needMoreData:
                return frames
            case .success(let type, let payload, let remaining):
                frames.append(Frame(type: type, payload: payload))
                buffer = remaining
            case .error(let message):
                throw AccumulateError.protocolError(message)
            }
        }
    }

    /// Bytes currently buffered awaiting more data (for tests/diagnostics).
    public var bufferedByteCount: Int { buffer.count }
}
