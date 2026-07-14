import Foundation

public struct FrameAccumulator {
    private var buffer = Data()

    public init() {}

    public struct Frame: Equatable {
        public let type: MessageType
        public let payload: Data
    }

    public enum AccumulateError: Error, Equatable {
        case protocolError(String)
    }

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

    public var bufferedByteCount: Int { buffer.count }
}
