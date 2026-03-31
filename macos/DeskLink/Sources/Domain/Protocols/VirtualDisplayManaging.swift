import Foundation

public protocol VirtualDisplayManaging: Sendable {
    func createDisplay(config: DisplayConfig) async throws
    func destroyDisplay() async
    func updateResolution(width: Int, height: Int) async throws
    var isDisplayActive: Bool { get async }
}
