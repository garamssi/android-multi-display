import Foundation

public protocol VirtualDisplayManaging: Sendable {
    func createDisplay(config: DisplayConfig) async throws
    func destroyDisplay() async

    var displayID: UInt32 { get }

    // The mode the display actually ended up in. CGVirtualDisplay is a private API and
    // can reject a requested mode, falling back to a default — so this is not always the
    // requested size, and the capture must follow this rather than the request.
    var activeResolution: (width: Int, height: Int)? { get }
}
