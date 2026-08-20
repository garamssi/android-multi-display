import Foundation

public struct EncodedFrame: Sendable {
    public let data: Data
    public let timestampUs: Int64
    public let isKeyframe: Bool
    public let frameNumber: UInt32

    public init(data: Data, timestampUs: Int64, isKeyframe: Bool, frameNumber: UInt32) {
        self.data = data
        self.timestampUs = timestampUs
        self.isKeyframe = isKeyframe
        self.frameNumber = frameNumber
    }

    public enum Flags {
        public static let isKeyframe: UInt8 = 0x01
        public static let isConfig: UInt8 = 0x02
    }

    public static let headerSize = 13

    public func serialize() -> Data {
        var payload = Data(capacity: Self.headerSize + data.count)

        withUnsafeBytes(of: UInt64(bitPattern: timestampUs).bigEndian) { payload.append(contentsOf: $0) }

        let flags: UInt8 = isKeyframe ? Flags.isKeyframe : 0
        payload.append(flags)

        withUnsafeBytes(of: frameNumber.bigEndian) { payload.append(contentsOf: $0) }

        payload.append(data)

        return payload
    }
}
