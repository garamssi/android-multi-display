import CoreAudio
import CoreMedia
import Foundation

/// The single time axis shared by the video and audio streams.
///
/// Lip-sync is only possible if a video frame's timestamp and an audio chunk's
/// timestamp are comparable, so every timestamp that goes on the wire is converted
/// here and nowhere else. The axis is the Core Audio host clock (mach ticks since
/// boot) expressed in microseconds — the domain ScreenCaptureKit stamps presentation
/// timestamps in and the domain `AudioTimeStamp.mHostTime` reports.
///
/// This type lives in `Data` because it wraps platform frameworks (CoreAudio /
/// CoreMedia). Domain models describe their timestamps as "host uptime microseconds"
/// and do not reference it.
///
/// Conversions use integer rescaling rather than `CMTimeGetSeconds`. Measured against
/// the previous `Double` path the difference is at most 1 microsecond over a realistic
/// uptime range — small, but this is the exact quantity audio is aligned against, and
/// routing every timestamp through one integer-exact conversion removes a rounding
/// boundary and gives both streams a single definition of the axis.
enum MediaClock {

    static let microsPerSecond: Int64 = 1_000_000
    private static let nanosPerMicro: UInt64 = 1_000

    /// Current time on the shared axis, in microseconds.
    static func nowUs() -> Int64 {
        Self.microsFromNanos(AudioConvertHostTimeToNanos(AudioGetCurrentHostTime()))
    }

    /// Converts a Core Audio host time (mach ticks, e.g. `AudioTimeStamp.mHostTime`)
    /// onto the shared axis.
    static func microsFromHostTime(_ hostTime: UInt64) -> Int64 {
        Self.microsFromNanos(AudioConvertHostTimeToNanos(hostTime))
    }

    /// Nanoseconds to microseconds, truncating. Split out from the Core Audio calls so
    /// the arithmetic is testable without a live clock.
    static func microsFromNanos(_ nanos: UInt64) -> Int64 {
        Int64(nanos / nanosPerMicro)
    }

    /// Converts a `CMTime` presentation timestamp onto the shared axis.
    ///
    /// Returns nil for a non-numeric time (invalid / indefinite). Nil rather than 0 is
    /// essential: 0 is a LEGAL point on this axis (the boot instant), so collapsing
    /// "unknown" into 0 makes the two indistinguishable — and a 0 fed into clock-domain
    /// calibration looks exactly like an epoch mismatch the size of the current uptime.
    static func microsFrom(_ time: CMTime) -> Int64? {
        guard time.isNumeric else { return nil }
        let rescaled = CMTimeConvertScale(time, timescale: CMTimeScale(microsPerSecond), method: .roundTowardZero)
        guard rescaled.isNumeric else { return nil }
        return rescaled.value
    }
}
