import Foundation

public enum AnnexBConverter {

    public static let startCode: [UInt8] = [0x00, 0x00, 0x00, 0x01]

    public enum ConversionError: Error, Equatable {
        case malformedAVCC
        case invalidLengthSize(Int)
    }

    public static func convert(avcc: Data, lengthSize: Int = 4) throws -> Data {
        guard lengthSize == 1 || lengthSize == 2 || lengthSize == 4 else {
            throw ConversionError.invalidLengthSize(lengthSize)
        }

        var output = Data(capacity: avcc.count + AnnexBConverter.startCode.count)

        try avcc.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            var offset = 0
            let total = raw.count

            while offset < total {
                guard offset + lengthSize <= total else {
                    throw ConversionError.malformedAVCC
                }

                var nalLength = 0
                for i in 0..<lengthSize {
                    nalLength = (nalLength << 8) | Int(raw.loadUnaligned(fromByteOffset: offset + i, as: UInt8.self))
                }
                offset += lengthSize

                guard nalLength >= 0, offset + nalLength <= total else {
                    throw ConversionError.malformedAVCC
                }

                output.append(contentsOf: AnnexBConverter.startCode)
                if nalLength > 0 {
                    let base = raw.baseAddress!.advanced(by: offset)
                    output.append(Data(bytes: base, count: nalLength))
                }
                offset += nalLength
            }
        }

        return output
    }
}
