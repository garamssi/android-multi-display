import Foundation

/// Failure modes of the macOS system-audio capture path. Kept separate from
/// `ConnectionError`, whose raw values are wire error codes defined in the protocol
/// spec — a Core Audio setup failure is a local concern, not a protocol one.
public enum AudioCaptureError: Error, CustomStringConvertible {
    /// No default output device to tap.
    case noDefaultOutputDevice
    /// A Core Audio call failed; carries the call site and the OSStatus.
    case coreAudioFailure(operation: String, status: Int32)
    /// The tap reported a PCM layout this build cannot convert.
    case unsupportedTapFormat(description: String)
    /// A Core Audio call reported success but produced no object.
    case unexpectedNilResult(operation: String)
    /// The aggregate device does not present the tap as its single input stream, so the
    /// IO proc cannot tell the tap's audio apart from another device's input (e.g. a
    /// headset microphone).
    case ambiguousInputStreams(channelCountsPerBuffer: [Int], expectedChannels: Int)

    public var description: String {
        switch self {
        case .noDefaultOutputDevice:
            return "No default audio output device is available to capture."
        case .coreAudioFailure(let operation, let status):
            return "Core Audio \(operation) failed with status \(status)."
        case .unsupportedTapFormat(let description):
            return "Unsupported tap audio format: \(description)."
        case .unexpectedNilResult(let operation):
            return "Core Audio \(operation) reported success but produced no object."
        case .ambiguousInputStreams(let channelCounts, let expected):
            return "Audio bridge exposes input streams \(channelCounts) but the tap needs "
                + "exactly one with \(expected) channels; refusing to capture the wrong source."
        }
    }
}
