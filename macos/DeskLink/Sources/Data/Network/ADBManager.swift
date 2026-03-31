import Foundation

/// Manages ADB port forwarding for USB connection mode.
public final class ADBManager: ADBManaging, @unchecked Sendable {
    private let lock = NSLock()
    private var isForwarding = false
    private var statusContinuation: AsyncStream<Bool>.Continuation?

    private let ports: [(local: UInt16, remote: UInt16)] = [
        (ProtocolConstants.portControl, ProtocolConstants.portControl),
        (ProtocolConstants.portVideo, ProtocolConstants.portVideo),
        (ProtocolConstants.portInput, ProtocolConstants.portInput),
    ]

    public var deviceStatusChanges: AsyncStream<Bool> {
        AsyncStream { continuation in
            lock.withLock {
                self.statusContinuation = continuation
            }
        }
    }

    public init() {}

    public func setupPortForwarding() async throws {
        for port in ports {
            let result = try await runADB("forward", "tcp:\(port.local)", "tcp:\(port.remote)")
            guard result.exitCode == 0 else {
                throw ConnectionError.refused
            }
        }
        lock.withLock {
            isForwarding = true
            statusContinuation?.yield(true)
        }
    }

    public func removePortForwarding() async throws {
        for port in ports {
            _ = try? await runADB("forward", "--remove", "tcp:\(port.local)")
        }
        lock.withLock {
            isForwarding = false
            statusContinuation?.yield(false)
        }
    }

    public func isDeviceConnected() async -> Bool {
        guard let result = try? await runADB("devices") else { return false }
        // Check if there's a device line (not just "List of devices attached")
        let lines = result.output.split(separator: "\n")
        return lines.contains { line in
            line.contains("\tdevice") && !line.contains("List of")
        }
    }

    // MARK: - Private

    private struct ADBResult {
        let exitCode: Int32
        let output: String
    }

    private static let adbPaths = ["/opt/homebrew/bin/adb", "/usr/local/bin/adb"]

    private func runADB(_ arguments: String...) async throws -> ADBResult {
        for path in Self.adbPaths {
            let process = Process()
            let pipe = Pipe()
            process.executableURL = URL(fileURLWithPath: path)
            process.arguments = arguments
            process.standardOutput = pipe
            process.standardError = pipe

            do {
                try process.run()
                process.waitUntilExit()

                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                let output = String(data: data, encoding: .utf8) ?? ""
                return ADBResult(exitCode: process.terminationStatus, output: output)
            } catch {
                continue // Try next path
            }
        }
        throw ConnectionError.refused
    }
}
