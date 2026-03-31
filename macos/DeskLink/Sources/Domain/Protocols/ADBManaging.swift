import Foundation

public protocol ADBManaging: Sendable {
    func setupPortForwarding() async throws
    func removePortForwarding() async throws
    func isDeviceConnected() async -> Bool
    var deviceStatusChanges: AsyncStream<Bool> { get }
}
