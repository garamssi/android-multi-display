import Foundation

/// Converts the 32-bit float PCM a Core Audio tap delivers into the signed 16-bit
/// little-endian PCM carried on the wire (see `AudioFormat.Encoding`).
///
/// Doing this on the Mac keeps the Android client's hot path free of per-sample work:
/// `AudioTrack`'s `ENCODING_PCM_16BIT` is native-endian, so the tablet can hand the
/// received bytes straight to the hardware.
enum PCMConverter {

    /// Size of one converted sample on the wire.
    static let bytesPerInt16Sample = 2

    /// Scale from the float range [-1, 1] onto signed 16-bit PCM.
    ///
    /// 32768 rather than 32767: two's complement is asymmetric, so scaling by 32768
    /// maps -1.0 exactly onto `Int16.min` and uses the full integer range. The cost is
    /// that +1.0 lands one step past `Int16.max`, which the clamp below absorbs — and
    /// the clamp has to be there regardless, for samples that exceed full scale.
    /// Literals rather than computed values: a `static let` initialized from an
    /// expression is lazy, which leaves a one-time-initialization check inside the
    /// per-sample loop after inlining. 32768 = -Int16.min, 32767 = Int16.max.
    private static let scaleFactor: Float = 32_768
    private static let upperBound: Float = 32_767
    private static let lowerBound: Float = -32_768

    /// Converts `sampleCount` interleaved float samples into `destination`, which must
    /// have room for `sampleCount * bytesPerInt16Sample` bytes.
    ///
    /// Real-time safe: no allocation, no branches beyond the clamp. Called from the
    /// tap's IO proc.
    static func writeInt16LittleEndian(
        from source: UnsafePointer<Float>,
        sampleCount: Int,
        into destination: UnsafeMutableRawPointer
    ) {
        let output = destination.assumingMemoryBound(to: UInt8.self)
        for index in 0..<sampleCount {
            let converted = int16Sample(from: source[index])
            let bitPattern = UInt16(bitPattern: converted)
            // Little-endian: low byte first.
            output[index * bytesPerInt16Sample] = UInt8(truncatingIfNeeded: bitPattern)
            output[index * bytesPerInt16Sample + 1] = UInt8(truncatingIfNeeded: bitPattern >> 8)
        }
    }

    /// Scales and clamps one float sample. Clamping (rather than letting the value
    /// wrap) matters because a source pushing past full scale would otherwise have its
    /// peaks sign-flipped, turning loud audio into harsh noise. A NaN becomes silence
    /// because converting it to an integer type traps in Swift.
    private static func int16Sample(from sample: Float) -> Int16 {
        guard !sample.isNaN else { return 0 }
        let scaled = (sample * scaleFactor).rounded()
        if scaled >= upperBound { return Int16.max }
        if scaled <= lowerBound { return Int16.min }
        return Int16(scaled)
    }
}
