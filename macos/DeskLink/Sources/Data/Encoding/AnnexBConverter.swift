import Foundation

/// Converts VideoToolbox AVCC (length-prefixed) NAL units to Annex-B (start-code
/// prefixed) format, which is what the DeskLink protocol carries end-to-end.
///
/// AVCC layout: `[len N bytes BE][NAL bytes] [len N bytes BE][NAL bytes] ...`
/// where `N` (the NAL unit header length) is typically 4 but is reported by the
/// format description (`nalUnitHeaderLength`).
///
/// Annex-B layout: `[00 00 00 01][NAL bytes] [00 00 00 01][NAL bytes] ...`
public enum AnnexBConverter {

    /// The 4-byte Annex-B start code.
    public static let startCode: [UInt8] = [0x00, 0x00, 0x00, 0x01]

    public enum ConversionError: Error, Equatable {
        /// The AVCC data is malformed (a length prefix runs past the end of the buffer).
        case malformedAVCC
        /// The NAL unit header length is unsupported (must be 1, 2, or 4).
        case invalidLengthSize(Int)
    }

    /// Converts a buffer of one or more AVCC length-prefixed NAL units to Annex-B.
    /// - Parameters:
    ///   - avcc: Concatenated AVCC NAL units as emitted by VideoToolbox.
    ///   - lengthSize: Bytes used for each NAL length prefix (1, 2, or 4). Default 4.
    /// - Returns: Annex-B formatted data (each NAL prefixed with `00 00 00 01`).
    public static func convert(avcc: Data, lengthSize: Int = 4) throws -> Data {
        guard lengthSize == 1 || lengthSize == 2 || lengthSize == 4 else {
            throw ConversionError.invalidLengthSize(lengthSize)
        }

        var output = Data(capacity: avcc.count + AnnexBConverter.startCode.count)

        try avcc.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            var offset = 0
            let total = raw.count

            while offset < total {
                // A length prefix must fully fit.
                guard offset + lengthSize <= total else {
                    throw ConversionError.malformedAVCC
                }

                // Read the Big-Endian length prefix.
                var nalLength = 0
                for i in 0..<lengthSize {
                    nalLength = (nalLength << 8) | Int(raw.loadUnaligned(fromByteOffset: offset + i, as: UInt8.self))
                }
                offset += lengthSize

                // The NAL body must fully fit.
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
