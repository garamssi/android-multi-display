import Foundation

/// How the tablet fits the picture to its own panel.
///
/// Mirror sends the Mac's own screen, whose shape has nothing to do with the tablet's, and
/// there is no way to show a 1.539 picture on a 1.600 panel without paying something: keep
/// every pixel and accept bars, or use the whole panel and lose the edges. So the user picks
/// which price. In extend mode the shapes match and the choice makes no difference.
public enum VideoScaling: Sendable, Equatable, CaseIterable {
    /// The whole picture, with bars where the shapes differ.
    case fit
    /// The whole panel, cropping whatever does not fit.
    case fill

    /// Part of the protocol, so the strings are fixed.
    public var wireValue: String {
        switch self {
        case .fit: return "fit"
        case .fill: return "fill"
        }
    }

    public init?(wireValue: String) {
        switch wireValue {
        case "fit": self = .fit
        case "fill": self = .fill
        default: return nil
        }
    }
}

/// The user's fit/fill choice.
///
/// `@unchecked Sendable` is the correct model rather than a silenced warning: `UserDefaults`
/// is documented as thread-safe and is the only stored state.
public final class VideoScalingPreference: @unchecked Sendable {

    public static let defaultsKey = "videoScaling"

    /// Fit, because it loses nothing. Cropping the Mac's screen without being asked would
    /// hide a menu bar or a dock edge and look like a bug.
    public static let defaultScaling = VideoScaling.fit

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public var scaling: VideoScaling {
        get {
            guard let stored = defaults.string(forKey: Self.defaultsKey),
                  let scaling = VideoScaling(wireValue: stored)
            else { return Self.defaultScaling }
            return scaling
        }
        set { defaults.set(newValue.wireValue, forKey: Self.defaultsKey) }
    }

    /// Emits the choice whenever the user changes it, so a running server can apply it
    /// without the user hunting for a Stop/Start.
    public static func scalingChanges(defaults: UserDefaults = .standard) -> AsyncStream<VideoScaling> {
        DefaultsChangeStream.make(defaults: defaults) { defaults in
            guard let stored = defaults.string(forKey: Self.defaultsKey),
                  let scaling = VideoScaling(wireValue: stored)
            else { return Self.defaultScaling }
            return scaling
        }
    }
}
