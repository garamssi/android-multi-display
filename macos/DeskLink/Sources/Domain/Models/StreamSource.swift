import Foundation

// What the server is streaming: a display to capture, the size to capture it at, and
// whether touches from the client may be injected into it.
//
// This is the single thing the two display modes disagree about. Extend creates a virtual
// display sized to the negotiated config; mirror streams a display that already exists
// and cannot be resized. Everything downstream — capture, encode, input — needs only a
// display id and a size, so keeping the difference here stops the mode from leaking into
// all three.
public struct StreamSource: Sendable, Equatable {
    public let displayID: UInt32
    public let captureWidth: Int
    public let captureHeight: Int

    // False for mirror: those touches would move the cursor on the screen the person at
    // the Mac is using. Enforced by not opening the input channel at all, so a client
    // that ignores the mode still cannot inject.
    public let acceptsInput: Bool

    public init(displayID: UInt32, captureWidth: Int, captureHeight: Int, acceptsInput: Bool) {
        self.displayID = displayID
        self.captureWidth = captureWidth
        self.captureHeight = captureHeight
        self.acceptsInput = acceptsInput
    }
}

// Prepares whatever the chosen mode needs before streaming, and releases it afterwards.
public protocol StreamSourceProviding: Sendable {
    func prepare(config: DisplayConfig) async throws -> StreamSource
    func teardown() async
}
