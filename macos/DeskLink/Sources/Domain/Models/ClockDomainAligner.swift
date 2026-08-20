import Foundation

/// Puts raw capture timestamps onto the shared audio/video axis, deciding by
/// measurement whether they are already on it.
///
/// Why this exists: ScreenCaptureKit presentation timestamps are host-clock based, but
/// there are reports of PTS values arriving from a different epoch than other host-time
/// sources. Assuming either way is unacceptable — if the video and audio stamps sit in
/// different epochs they land seconds apart and lip-sync fails with no signal at all.
///
/// Two separate concerns are handled, and conflating them was a real defect:
///
/// 1. **Epoch mismatch** is enormous (uptime-scale) so one sample settles it. It is
///    decided on the FIRST sample and applied to that same sample, so no frame ever
///    leaves on a different axis than its neighbours.
/// 2. **Capture latency** is tens of milliseconds and varies per sample. It must NOT
///    end up baked into the correction, because the audio path would carry a different
///    first-sample latency and the two streams would sit permanently offset. So the
///    correction is refined over `calibrationSampleCount` samples using the SMALLEST
///    observed offset — the sample that waited least is the one closest to the true
///    epoch difference.
///
/// `align(rawUs:nowUs:)` both records the observation and returns the corrected value,
/// so it is structurally impossible to apply a correction that has not been measured yet.
public struct ClockDomainAligner {

    /// Distance from "now" beyond which an offset cannot be capture latency. Capture
    /// pipelines run tens of milliseconds behind; one second is two orders of magnitude
    /// past that, and far below any epoch-sized discrepancy.
    public static let mismatchThresholdUs: Int64 = 1_000_000

    /// Samples used to refine the correction before it is frozen. Enough to see past a
    /// slow first frame, short enough to finish within the first second of streaming.
    public static let calibrationSampleCount = 8

    public enum State: Equatable {
        /// Timestamps already share the axis; no correction is applied.
        case aligned(latencyUs: Int64)
        /// A different epoch was detected and is being removed.
        case corrected(offsetUs: Int64)
    }

    /// Offset added to raw timestamps. Zero unless a mismatch was detected.
    public private(set) var correctionUs: Int64 = 0

    /// Nil until the first sample is observed.
    public private(set) var state: State?

    /// Smallest offset seen so far while calibrating.
    private var smallestOffsetUs: Int64 = 0
    private var samplesObserved = 0

    public init() {}

    /// True while the correction may still be refined by further samples.
    public var isCalibrating: Bool {
        samplesObserved > 0 && samplesObserved < Self.calibrationSampleCount
    }

    /// Records one raw timestamp and returns it on the shared axis.
    ///
    /// `rawTimestampUs` must be a real timestamp. Unknown timestamps are represented as nil by
    /// `MediaClock.microsFrom` and must never be passed here: feeding a placeholder in
    /// would be read as an epoch mismatch the size of the current uptime and would
    /// corrupt every subsequent timestamp in the session.
    public mutating func align(rawTimestampUs: Int64, nowUs: Int64) -> Int64 {
        let offsetUs = nowUs &- rawTimestampUs

        if samplesObserved == 0 || offsetUs < smallestOffsetUs {
            smallestOffsetUs = offsetUs
        }
        samplesObserved += 1

        // Freeze after calibration so a late outlier cannot shift the axis mid-stream.
        if samplesObserved <= Self.calibrationSampleCount {
            // `magnitude` rather than `abs`: `abs(Int64.min)` overflows and traps, and
            // the wrapping subtraction above can produce exactly that value.
            if smallestOffsetUs.magnitude > Self.mismatchThresholdUs.magnitude {
                correctionUs = smallestOffsetUs
                state = .corrected(offsetUs: smallestOffsetUs)
            } else {
                correctionUs = 0
                state = .aligned(latencyUs: smallestOffsetUs)
            }
        }

        return rawTimestampUs &+ correctionUs
    }
}
