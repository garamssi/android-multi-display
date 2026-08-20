import ScreenCaptureKit
import XCTest
@testable import DeskLink

/// Classifies why ScreenCaptureKit ended a capture stream.
///
/// This matters because the two cases need opposite handling. When the USER stops sharing
/// from the system's screen-capture indicator, that is an explicit "I am done" — the whole
/// session must end, including audio, and the UI must offer Start again. Any other failure
/// is a broken stream that a reconnect may recover, so the server keeps listening.
///
/// Before this existed, a user-stop only ended the video stream: the server kept running,
/// the audio tap kept streaming sound to the tablet, and the menu still showed "Stop
/// Server" with no way to restart sharing.
final class CaptureStopReasonTests: XCTestCase {

    /// Built from the raw code so the test also pins the numeric contract, and so it
    /// compiles against the package's macOS 14 deployment target (`systemStoppedStream`
    /// is a macOS 15 symbol, but its code exists in the domain regardless).
    private func streamError(_ code: Int) -> NSError {
        NSError(domain: SCStreamErrorDomain, code: code)
    }

    private let userStopped = -3817
    private let systemStopped = -3821

    /// The system indicator's "Stop Sharing" button.
    func testUserStoppedEndsTheSession() {
        XCTAssertEqual(CaptureStopReason(error: streamError(userStopped)), .endedByUser)
    }

    /// macOS itself pulled the stream (for example a security policy change).
    func testSystemStoppedEndsTheSession() {
        XCTAssertEqual(CaptureStopReason(error: streamError(systemStopped)), .endedByUser)
    }

    /// Losing the capture source is a failure, not an intent to stop: the display may come
    /// back, and a reconnect rebuilds the pipeline.
    func testTransientStreamFailureIsRecoverable() {
        XCTAssertEqual(CaptureStopReason(error: streamError(SCStreamError.Code.noCaptureSource.rawValue)), .failure)
        XCTAssertEqual(CaptureStopReason(error: streamError(SCStreamError.Code.internalError.rawValue)), .failure)
        XCTAssertEqual(CaptureStopReason(error: streamError(SCStreamError.Code.failedApplicationConnectionInterrupted.rawValue)), .failure)
    }

    /// A permissions revocation is a failure too — the session should not silently end as
    /// though the user asked for it; the error path reports it.
    func testUserDeclinedIsAFailureNotAUserStop() {
        XCTAssertEqual(CaptureStopReason(error: streamError(SCStreamError.Code.userDeclined.rawValue)), .failure)
    }

    /// Errors from anywhere else must never be read as a user stop, or an unrelated
    /// failure would tear the whole session down.
    func testNonStreamKitErrorIsAFailure() {
        XCTAssertEqual(CaptureStopReason(error: ConnectionError.lost), .failure)
        XCTAssertEqual(
            CaptureStopReason(error: NSError(domain: NSPOSIXErrorDomain, code: -3817)),
            .failure,
            "a matching code in a different domain must not be treated as a user stop"
        )
    }
}
