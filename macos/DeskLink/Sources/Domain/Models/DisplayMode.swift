import Foundation

// How the tablet's screen relates to the Mac's.
public enum DisplayMode: Sendable, Equatable, CaseIterable {
    // A new display is added to the Mac; the tablet shows content the Mac's own screen
    // does not.
    case extend
    // The tablet shows the same content as one of the Mac's existing displays.
    case mirror

    // Only extend creates a display. Mirror streams one the user already has, so nothing
    // is created and nothing may be destroyed on teardown.
    public var createsVirtualDisplay: Bool { self == .extend }

    // Mirror refuses input: those touches would move the cursor on the screen the person
    // at the Mac is using.
    public var acceptsInput: Bool { self == .extend }

    // Sent to the client so it can skip the input channel and show that touch is off.
    // Part of the protocol, so the strings are fixed.
    public var wireValue: String {
        switch self {
        case .extend: return "extend"
        case .mirror: return "mirror"
        }
    }

    public init?(wireValue: String) {
        switch wireValue {
        case "extend": self = .extend
        case "mirror": self = .mirror
        default: return nil
        }
    }
}

// The user's extend/mirror choice.
//
// `@unchecked Sendable` is the correct model rather than a silenced warning: `UserDefaults`
// is documented as thread-safe and is the only stored state.
public final class DisplayModePreference: @unchecked Sendable {

    public static let defaultsKey = "displayMode"

    // Extend is what the app has always done; mirror taking over the Mac's own screen
    // without being asked would be a surprising change.
    public static let defaultMode = DisplayMode.extend

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Emits the mode whenever the user changes it, so a running server can rebuild the
    /// session itself.
    ///
    /// The alternative was making the user press Stop then Start on the Mac. That loses the
    /// tablet: the client gives up after `RECONNECT_MAX_ATTEMPTS` tries one second apart, so
    /// a hand-timed restart has a few seconds to land or the tablet needs touching too.
    public static func modeChanges(defaults: UserDefaults = .standard) -> AsyncStream<DisplayMode> {
        DefaultsChangeStream.make(defaults: defaults) { defaults in
            guard let stored = defaults.string(forKey: Self.defaultsKey),
                  let mode = DisplayMode(wireValue: stored)
            else { return Self.defaultMode }
            return mode
        }
    }

    public var mode: DisplayMode {
        get {
            guard let stored = defaults.string(forKey: Self.defaultsKey),
                  let mode = DisplayMode(wireValue: stored)
            else { return Self.defaultMode }
            return mode
        }
        set { defaults.set(newValue.wireValue, forKey: Self.defaultsKey) }
    }
}
