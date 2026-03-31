import XCTest
@testable import DeskLink

final class CaptureVerifierTests: XCTestCase {

    func testSaveAsPNGWithValidData() throws {
        // Create minimal 2x2 BGRA image data
        let width = 2
        let height = 2
        let bytesPerPixel = 4
        let data = Data(repeating: 0xFF, count: width * height * bytesPerPixel)
        let frame = VideoFrame(data: data, timestampUs: 0, isKeyframe: false)

        let tempPath = NSTemporaryDirectory() + "desklink_test_\(UUID().uuidString).png"
        defer { try? FileManager.default.removeItem(atPath: tempPath) }

        XCTAssertNoThrow(try CaptureVerifier.saveAsPNG(frame: frame, width: width, height: height, path: tempPath))
        XCTAssertTrue(FileManager.default.fileExists(atPath: tempPath))
    }

    func testSaveAsPNGWithInsufficientDataThrows() {
        // Data too small for the specified dimensions
        let data = Data(repeating: 0, count: 10)
        let frame = VideoFrame(data: data, timestampUs: 0, isKeyframe: false)

        let tempPath = NSTemporaryDirectory() + "desklink_test_fail.png"

        XCTAssertThrowsError(try CaptureVerifier.saveAsPNG(frame: frame, width: 100, height: 100, path: tempPath))
    }
}
