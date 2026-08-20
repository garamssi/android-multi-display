import CoreMedia
import XCTest
@testable import DeskLink

/// The audio and video streams can only be lip-synced if their timestamps live on one
/// shared axis. `MediaClock` owns that axis; these tests pin the conversions that feed it.
final class MediaClockTests: XCTestCase {

    // MARK: - Nanosecond conversion

    func testNanosToMicrosTruncatesTowardZero() {
        XCTAssertEqual(MediaClock.microsFromNanos(1_000), 1)
        XCTAssertEqual(MediaClock.microsFromNanos(1_999), 1)
        XCTAssertEqual(MediaClock.microsFromNanos(0), 0)
    }

    func testNanosToMicrosHandlesLargeUptime() {
        let hundredDaysNanos: UInt64 = 100 * 24 * 60 * 60 * 1_000_000_000
        XCTAssertEqual(MediaClock.microsFromNanos(hundredDaysNanos), 100 * 24 * 60 * 60 * 1_000_000)
    }

    // MARK: - CMTime conversion

    func testPresentationTimeConvertsExactly() {
        XCTAssertEqual(MediaClock.microsFrom(CMTime(value: 20, timescale: 600)), 33_333)
        XCTAssertEqual(MediaClock.microsFrom(CMTime(value: 1, timescale: 1)), 1_000_000)
        XCTAssertEqual(MediaClock.microsFrom(CMTime(value: 0, timescale: 600)), 0)
    }

    func testPresentationTimeIsExactForLargeHostClockValues() {
        XCTAssertEqual(
            MediaClock.microsFrom(CMTime(value: 1_000_000_000, timescale: 1_000_000_000)),
            1_000_000
        )
        XCTAssertEqual(
            MediaClock.microsFrom(CMTime(value: 123_456_789_123, timescale: 1_000_000_000)),
            123_456_789
        )
    }

    /// A non-numeric time must map to nil, NOT to 0. Zero is a legal point on this axis
    /// (the boot instant), so collapsing "unknown" into 0 makes them indistinguishable —
    /// and a 0 handed to clock calibration looks exactly like an uptime-sized epoch
    /// mismatch, which would corrupt every timestamp for the rest of the session.
    func testNonNumericPresentationTimeIsNilNotZero() {
        XCTAssertNil(MediaClock.microsFrom(CMTime.invalid))
        XCTAssertNil(MediaClock.microsFrom(CMTime.indefinite))
        XCTAssertNil(MediaClock.microsFrom(CMTime.positiveInfinity))
        XCTAssertNil(MediaClock.microsFrom(CMTime.negativeInfinity))
    }

    /// A genuine zero timestamp must still come through as a value.
    func testZeroIsAValueNotUnknown() {
        XCTAssertEqual(MediaClock.microsFrom(CMTime.zero), 0)
    }

    // MARK: - Host time

    func testHostTimeConversionIsMonotonic() {
        let first = MediaClock.microsFromHostTime(AudioGetCurrentHostTime())
        let second = MediaClock.microsFromHostTime(AudioGetCurrentHostTime())
        XCTAssertGreaterThan(first, 0)
        XCTAssertGreaterThanOrEqual(second, first)
    }

    /// The video axis (`microsFrom`) and the audio axis (`microsFromHostTime`) must be
    /// the SAME axis — this is the invariant lip-sync rests on. Host-clock ticks fed
    /// through both paths must agree.
    func testVideoAndAudioAxesAgree() {
        let hostTime = AudioGetCurrentHostTime()
        let audioAxisUs = MediaClock.microsFromHostTime(hostTime)
        let nowUs = MediaClock.nowUs()
        // Both derive from the same host clock, so a stamp taken microseconds earlier
        // must be within a small window of "now" — not epochs apart.
        XCTAssertLessThan(abs(nowUs - audioAxisUs), MediaClock.microsPerSecond)
    }

    func testNowIsMonotonicAndPositive() {
        let first = MediaClock.nowUs()
        let second = MediaClock.nowUs()
        XCTAssertGreaterThan(first, 0)
        XCTAssertGreaterThanOrEqual(second, first)
    }
}

/// Verifies the runtime alignment of capture timestamps onto the shared axis.
final class ClockDomainAlignerTests: XCTestCase {

    func testSameDomainAppliesNoCorrection() {
        var aligner = ClockDomainAligner()
        // Stamped 8ms before observation: ordinary capture latency.
        XCTAssertEqual(aligner.align(rawTimestampUs: 1_000_000, nowUs: 1_008_000), 1_000_000)
        XCTAssertEqual(aligner.correctionUs, 0)
        XCTAssertEqual(aligner.state, .aligned(latencyUs: 8_000))
    }

    /// A stamp slightly in the FUTURE is still the same domain: capture pipelines
    /// legitimately report a presentation time a frame ahead.
    func testSmallNegativeOffsetIsStillAligned() {
        var aligner = ClockDomainAligner()
        XCTAssertEqual(aligner.align(rawTimestampUs: 1_020_000, nowUs: 1_000_000), 1_020_000)
        XCTAssertEqual(aligner.correctionUs, 0)
    }

    /// The FIRST sample must come out already corrected. Correcting from the second
    /// sample onward would leave frame #1 on a different epoch than its neighbours, and
    /// a client that anchors its sync on the first frame would anchor on a bad value.
    func testFirstSampleIsItselfCorrected() {
        var aligner = ClockDomainAligner()
        let epochGapUs: Int64 = 60 * 1_000_000
        let aligned = aligner.align(rawTimestampUs: 1_000_000, nowUs: 1_000_000 + epochGapUs)
        XCTAssertEqual(aligned, 1_000_000 + epochGapUs)
        XCTAssertEqual(aligner.state, .corrected(offsetUs: epochGapUs))
    }

    func testSubsequentSamplesGetTheSameCorrection() {
        var aligner = ClockDomainAligner()
        let epochGapUs: Int64 = 60 * 1_000_000
        _ = aligner.align(rawTimestampUs: 1_000_000, nowUs: 1_000_000 + epochGapUs)
        XCTAssertEqual(aligner.align(rawTimestampUs: 2_000_000, nowUs: 2_000_000 + epochGapUs), 2_000_000 + epochGapUs)
    }

    /// Capture latency must not be baked into the correction. The first sample may be
    /// unusually slow; the smallest offset over the calibration window is the one closest
    /// to the true epoch difference. A latency-polluted correction would offset the video
    /// axis relative to the audio axis permanently.
    func testCorrectionConvergesToSmallestOffsetNotTheFirst() {
        var aligner = ClockDomainAligner()
        let epochGapUs: Int64 = 60 * 1_000_000

        // First frame is 200ms late; later frames only 5ms.
        _ = aligner.align(rawTimestampUs: 1_000_000, nowUs: 1_000_000 + epochGapUs + 200_000)
        XCTAssertEqual(aligner.correctionUs, epochGapUs + 200_000)

        _ = aligner.align(rawTimestampUs: 1_100_000, nowUs: 1_100_000 + epochGapUs + 5_000)
        XCTAssertEqual(aligner.correctionUs, epochGapUs + 5_000)
    }

    /// Once calibration ends the axis must stop moving: a late outlier shifting the
    /// correction mid-stream is worse than a small stable error.
    func testCorrectionFreezesAfterCalibrationWindow() {
        var aligner = ClockDomainAligner()
        let epochGapUs: Int64 = 60 * 1_000_000
        for _ in 0..<ClockDomainAligner.calibrationSampleCount {
            _ = aligner.align(rawTimestampUs: 1_000_000, nowUs: 1_000_000 + epochGapUs + 10_000)
        }
        let frozen = aligner.correctionUs
        XCTAssertFalse(aligner.isCalibrating)

        // A wildly smaller offset after the window must be ignored.
        _ = aligner.align(rawTimestampUs: 1_000_000, nowUs: 1_000_000 + epochGapUs)
        XCTAssertEqual(aligner.correctionUs, frozen)
    }

    func testIsCalibratingReflectsWindow() {
        var aligner = ClockDomainAligner()
        XCTAssertFalse(aligner.isCalibrating, "no samples yet")
        _ = aligner.align(rawTimestampUs: 1_000_000, nowUs: 1_008_000)
        XCTAssertTrue(aligner.isCalibrating)
    }

    /// Extreme inputs must not trap. The production path cannot reach these, but
    /// unchecked subtraction on Int64 extremes is a crash rather than a wrong answer.
    func testExtremeInputsDoNotTrap() {
        var aligner = ClockDomainAligner()
        _ = aligner.align(rawTimestampUs: Int64.min, nowUs: 0)
        _ = aligner.align(rawTimestampUs: Int64.max, nowUs: Int64.min)
    }

    /// The threshold must sit strictly between plausible capture latency and an
    /// epoch-sized gap. Asserting only a lower bound would let a 1-hour threshold pass.
    func testThresholdSitsBetweenLatencyAndEpochScale() {
        let plausibleCaptureLatencyUs: Int64 = 500_000
        let smallestEpochGapUs: Int64 = 10 * MediaClock.microsPerSecond
        XCTAssertGreaterThan(ClockDomainAligner.mismatchThresholdUs, plausibleCaptureLatencyUs)
        XCTAssertLessThan(ClockDomainAligner.mismatchThresholdUs, smallestEpochGapUs)
    }
}
