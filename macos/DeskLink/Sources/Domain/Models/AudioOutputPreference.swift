import Foundation

/// The user's "play Mac audio on the tablet" choice.
///
/// Defaults to OFF deliberately. While audio is routed to the tablet the Mac's own
/// output is suppressed, and having that happen merely because the user started screen
/// mirroring would be a surprising loss of sound. Turning it off releases the tap, and
/// the speakers come back.
/// `@unchecked Sendable` is the correct model here rather than a silenced warning:
/// `UserDefaults` is documented as thread-safe and is the only stored state, so no
/// additional synchronization exists to get wrong. `Log` relies on the same property.
public final class AudioOutputPreference: @unchecked Sendable {

    public static let defaultsKey = "audioRouteToTablet"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// When true, system audio is captured and sent to the connected tablet instead of
    /// playing on the Mac.
    public var routeToTablet: Bool {
        get { defaults.bool(forKey: Self.defaultsKey) }
        set { defaults.set(newValue, forKey: Self.defaultsKey) }
    }
}
