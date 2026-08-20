import Foundation

/// Resolves the path to the `adb` executable.
///
/// Why this exists: adb has no single canonical location on macOS. Android Studio
/// installs it under `~/Library/Android/sdk/platform-tools`, Homebrew places a shim
/// in `/opt/homebrew/bin` (Apple silicon) or `/usr/local/bin` (Intel), and custom or
/// CI setups only export `ANDROID_HOME`/`ANDROID_SDK_ROOT`. A fixed path list that
/// misses the user's install makes every `adb reverse`/`adb devices` call fail, which
/// leaves the reverse tunnel unestablished and the tablet unable to reach the Mac.
///
/// Resolution is intentionally not cached: it is a handful of `isExecutableFile`
/// probes, and a cached miss would keep failing after the user installs the SDK.
enum ADBLocator {

    /// Environment variables that point at an Android SDK root, most specific first.
    /// `ANDROID_HOME` is the current name; `ANDROID_SDK_ROOT` is the deprecated one
    /// still exported by older tooling and CI images.
    static let sdkRootEnvironmentKeys = ["ANDROID_HOME", "ANDROID_SDK_ROOT"]

    /// Path of adb relative to an SDK root.
    static let platformToolsRelativePath = "platform-tools/adb"

    /// Android Studio's default SDK root on macOS, relative to the home directory.
    static let defaultSDKRootRelativePath = "Library/Android/sdk"

    /// Homebrew shim locations (Apple silicon prefix first, then Intel).
    static let homebrewPaths = ["/opt/homebrew/bin/adb", "/usr/local/bin/adb"]

    /// Resolves adb using the live process environment and home directory.
    static func resolve() -> String? {
        resolve(
            environment: ProcessInfo.processInfo.environment,
            homeDirectory: NSHomeDirectory(),
            isExecutable: { FileManager.default.isExecutableFile(atPath: $0) }
        )
    }

    /// Resolves adb against an injected environment, home directory and executable
    /// probe. Returns the first candidate that is actually runnable, or nil if none is.
    static func resolve(
        environment: [String: String],
        homeDirectory: String,
        isExecutable: (String) -> Bool
    ) -> String? {
        candidatePaths(environment: environment, homeDirectory: homeDirectory)
            .first(where: isExecutable)
    }

    /// Candidate adb paths in preference order: an explicitly configured SDK first
    /// (the user named it, so it must not be shadowed), then Android Studio's default
    /// SDK, then the Homebrew shims.
    static func candidatePaths(environment: [String: String], homeDirectory: String) -> [String] {
        var roots = sdkRootEnvironmentKeys.compactMap { key -> String? in
            guard let value = environment[key]?.trimmingCharacters(in: .whitespaces),
                  !value.isEmpty
            else { return nil }
            return value
        }
        roots.append((homeDirectory as NSString).appendingPathComponent(defaultSDKRootRelativePath))

        let sdkCandidates = roots.map {
            ($0 as NSString).appendingPathComponent(platformToolsRelativePath)
        }
        return sdkCandidates + homebrewPaths
    }
}

/// Failure modes of invoking the `adb` tool itself (as opposed to the wire-level
/// `ConnectionError` codes, whose raw values are part of the protocol spec).
enum ADBError: Error, CustomStringConvertible {
    /// No runnable `adb` was found at any candidate path.
    case executableNotFound(searched: [String])

    var description: String {
        switch self {
        case .executableNotFound(let searched):
            return """
                adb not found. Install Android platform-tools, or set ANDROID_HOME to \
                your SDK root. Searched: \(searched.joined(separator: ", "))
                """
        }
    }
}
