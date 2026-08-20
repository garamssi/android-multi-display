import XCTest
@testable import DeskLink

/// Verifies `adb` executable discovery.
///
/// Regression: the resolver used to be a hardcoded two-entry list
/// (`/opt/homebrew/bin/adb`, `/usr/local/bin/adb`). On a Mac where adb comes from
/// Android Studio's SDK (`~/Library/Android/sdk/platform-tools/adb`) neither path
/// exists, so every `adb reverse`/`adb devices` call failed, the reverse tunnel was
/// never created, and the tablet could not reach the Mac at all.
final class ADBLocatorTests: XCTestCase {

    /// Builds an executable-probe that reports exactly `paths` as runnable.
    private func probe(_ paths: String...) -> @Sendable (String) -> Bool {
        let set = Set(paths)
        return { set.contains($0) }
    }

    func testResolvesAndroidStudioSDKLocation() {
        let expected = "/Users/tester/Library/Android/sdk/platform-tools/adb"
        XCTAssertEqual(
            ADBLocator.resolve(
                environment: [:],
                homeDirectory: "/Users/tester",
                isExecutable: probe(expected)
            ),
            expected
        )
    }

    func testResolvesHomebrewAppleSiliconPath() {
        XCTAssertEqual(
            ADBLocator.resolve(
                environment: [:],
                homeDirectory: "/Users/tester",
                isExecutable: probe("/opt/homebrew/bin/adb")
            ),
            "/opt/homebrew/bin/adb"
        )
    }

    func testResolvesHomebrewIntelPath() {
        XCTAssertEqual(
            ADBLocator.resolve(
                environment: [:],
                homeDirectory: "/Users/tester",
                isExecutable: probe("/usr/local/bin/adb")
            ),
            "/usr/local/bin/adb"
        )
    }

    func testResolvesAndroidHomeEnvironment() {
        XCTAssertEqual(
            ADBLocator.resolve(
                environment: ["ANDROID_HOME": "/opt/android-sdk"],
                homeDirectory: "/Users/tester",
                isExecutable: probe("/opt/android-sdk/platform-tools/adb")
            ),
            "/opt/android-sdk/platform-tools/adb"
        )
    }

    func testResolvesAndroidSdkRootEnvironment() {
        XCTAssertEqual(
            ADBLocator.resolve(
                environment: ["ANDROID_SDK_ROOT": "/opt/sdk-root"],
                homeDirectory: "/Users/tester",
                isExecutable: probe("/opt/sdk-root/platform-tools/adb")
            ),
            "/opt/sdk-root/platform-tools/adb"
        )
    }

    /// An explicitly configured SDK must win over any default location, so a user
    /// pointing at a specific SDK is not silently served a different adb.
    func testEnvironmentWinsOverDefaultLocations() {
        let envPath = "/opt/android-sdk/platform-tools/adb"
        let sdkPath = "/Users/tester/Library/Android/sdk/platform-tools/adb"
        XCTAssertEqual(
            ADBLocator.resolve(
                environment: ["ANDROID_HOME": "/opt/android-sdk"],
                homeDirectory: "/Users/tester",
                isExecutable: probe(envPath, sdkPath, "/opt/homebrew/bin/adb")
            ),
            envPath
        )
    }

    /// The Android Studio SDK is the adb that ships with the platform the app targets,
    /// so it is preferred over a Homebrew shim when both are present.
    func testSDKLocationWinsOverHomebrew() {
        let sdkPath = "/Users/tester/Library/Android/sdk/platform-tools/adb"
        XCTAssertEqual(
            ADBLocator.resolve(
                environment: [:],
                homeDirectory: "/Users/tester",
                isExecutable: probe(sdkPath, "/opt/homebrew/bin/adb", "/usr/local/bin/adb")
            ),
            sdkPath
        )
    }

    /// A blank or whitespace-only env value must not produce the bogus candidate
    /// "/platform-tools/adb"; it should be ignored like an unset variable.
    func testIgnoresEmptyEnvironmentValue() {
        let sdkPath = "/Users/tester/Library/Android/sdk/platform-tools/adb"
        XCTAssertEqual(
            ADBLocator.resolve(
                environment: ["ANDROID_HOME": "  "],
                homeDirectory: "/Users/tester",
                isExecutable: probe(sdkPath)
            ),
            sdkPath
        )
    }

    func testReturnsNilWhenNoCandidateIsExecutable() {
        XCTAssertNil(
            ADBLocator.resolve(
                environment: [:],
                homeDirectory: "/Users/tester",
                isExecutable: { _ in false }
            )
        )
    }

    /// A non-executable file at a candidate path must be skipped, not returned —
    /// otherwise a stale/partial SDK download shadows a working adb.
    func testSkipsNonExecutableCandidate() {
        XCTAssertEqual(
            ADBLocator.resolve(
                environment: ["ANDROID_HOME": "/opt/android-sdk"],
                homeDirectory: "/Users/tester",
                isExecutable: probe("/opt/homebrew/bin/adb")
            ),
            "/opt/homebrew/bin/adb"
        )
    }
}
