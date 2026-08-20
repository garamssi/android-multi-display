import Foundation

/// Why a screen-capture stream ended.
///
/// The distinction drives opposite behaviour. A user stopping the share from the system's
/// capture indicator is an explicit end of session: everything must stop, audio included,
/// and the UI must offer Start again. Anything else is a broken stream that a client
/// reconnect can rebuild, so the server stays up.
public enum CaptureStopReason: Equatable {
    /// The user (or the system on their behalf) ended screen sharing.
    case endedByUser
    /// The stream broke; a reconnect may recover it.
    case failure

    /// The ScreenCaptureKit error domain. Matched by name rather than by importing
    /// ScreenCaptureKit here, so this domain type stays free of the platform framework.
    static let screenCaptureKitErrorDomain = "com.apple.ScreenCaptureKit.SCStreamErrorDomain"

    /// `SCStreamErrorUserStopped`: the user pressed Stop Sharing.
    static let userStoppedCode = -3817
    /// `SCStreamErrorSystemStoppedStream`: macOS ended the stream itself.
    static let systemStoppedCode = -3821

    public init(error: Error) {
        let nsError = error as NSError
        guard nsError.domain == Self.screenCaptureKitErrorDomain else {
            // A matching numeric code in another domain means something unrelated; only
            // ScreenCaptureKit's own codes describe a share being stopped.
            self = .failure
            return
        }
        switch nsError.code {
        case Self.userStoppedCode, Self.systemStoppedCode:
            self = .endedByUser
        default:
            self = .failure
        }
    }
}
