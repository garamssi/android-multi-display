import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

/// Utility to save raw captured frames as PNG for verification.
/// Used during development to confirm that virtual display + capture works correctly.
public enum CaptureVerifier {

    /// Saves raw BGRA pixel data as a PNG file.
    /// - Parameters:
    ///   - frame: The captured VideoFrame with raw BGRA data
    ///   - width: Image width in pixels
    ///   - height: Image height in pixels
    ///   - path: File path to save the PNG
    /// - Throws: ConnectionError.displayCaptureFailed if save fails
    public static func saveAsPNG(frame: VideoFrame, width: Int, height: Int, path: String) throws {
        let bytesPerRow = width * 4 // BGRA = 4 bytes per pixel
        let expectedSize = bytesPerRow * height

        guard frame.data.count >= expectedSize else {
            throw ConnectionError.displayCaptureFailed
        }

        let colorSpace = CGColorSpaceCreateDeviceRGB()

        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else {
            throw ConnectionError.displayCaptureFailed
        }

        // Copy frame data into the context
        guard let contextData = context.data else {
            throw ConnectionError.displayCaptureFailed
        }
        frame.data.withUnsafeBytes { rawBuffer in
            contextData.copyMemory(from: rawBuffer.baseAddress!, byteCount: expectedSize)
        }

        guard let cgImage = context.makeImage() else {
            throw ConnectionError.displayCaptureFailed
        }

        let url = URL(fileURLWithPath: path) as CFURL
        guard let destination = CGImageDestinationCreateWithURL(url, UTType.png.identifier as CFString, 1, nil) else {
            throw ConnectionError.displayCaptureFailed
        }

        CGImageDestinationAddImage(destination, cgImage, nil)

        guard CGImageDestinationFinalize(destination) else {
            throw ConnectionError.displayCaptureFailed
        }
    }
}
