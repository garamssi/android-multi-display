import Foundation

// Transport is `adb reverse` (device -> host); never `adb forward`.
// S-H1: status AsyncStream + continuation are created once in init and stored; do not rebuild per access (overwrites the continuation and drops subscribers).
public final class ADBManager: ADBManaging, @unchecked Sendable {
    private let lock = NSLock()
    private var isForwarding = false

    private let statusStream: AsyncStream<Bool>
    private let statusContinuation: AsyncStream<Bool>.Continuation

    private let adbQueue = DispatchQueue(label: "com.desklink.adb", qos: .utility)

    private let ports: [(devicePort: UInt16, hostPort: UInt16)] = [
        (ProtocolConstants.portControl, ProtocolConstants.portControl),
        (ProtocolConstants.portVideo, ProtocolConstants.portVideo),
        (ProtocolConstants.portInput, ProtocolConstants.portInput),
    ]

    public var deviceStatusChanges: AsyncStream<Bool> {
        statusStream
    }

    public init() {
        var continuation: AsyncStream<Bool>.Continuation!
        statusStream = AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation = $0 }
        statusContinuation = continuation
    }

    deinit {
        statusContinuation.finish()
    }

    public func setupPortForwarding() async throws {
        for port in ports {
            let result = try await runADB("reverse", "tcp:\(port.devicePort)", "tcp:\(port.hostPort)")
            guard result.exitCode == 0 else {
                throw ConnectionError.refused
            }
        }
        lock.withLock { isForwarding = true }
        statusContinuation.yield(true)
    }

    public func removePortForwarding() async throws {
        for port in ports {
            _ = try? await runADB("reverse", "--remove", "tcp:\(port.devicePort)")
        }
        lock.withLock { isForwarding = false }
        statusContinuation.yield(false)
    }

    public func isDeviceConnected() async -> Bool {
        guard let result = try? await runADB("devices") else { return false }
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
        let args = arguments
        return try await withCheckedThrowingContinuation { continuation in
            adbQueue.async {
                do {
                    let result = try Self.runADBBlocking(arguments: args)
                    continuation.resume(returning: result)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private static func runADBBlocking(arguments: [String]) throws -> ADBResult {
        for path in adbPaths {
            guard FileManager.default.isExecutableFile(atPath: path) else { continue }

            let process = Process()
            let stdoutPipe = Pipe()
            let stderrPipe = Pipe()
            process.executableURL = URL(fileURLWithPath: path)
            process.arguments = arguments
            process.standardOutput = stdoutPipe
            process.standardError = stderrPipe

            do {
                try process.run()
            } catch {
                continue // Try next path
            }

            // S-H3: drain both pipes to EOF BEFORE waitUntilExit, or a chatty child stalls on a full pipe while we wait on exit (deadlock).
            let outData = stdoutPipe.fileHandleForReading.readDataToEndOfFile()
            let errData = stderrPipe.fileHandleForReading.readDataToEndOfFile()

            process.waitUntilExit()

            var output = String(data: outData, encoding: .utf8) ?? ""
            if output.isEmpty {
                output = String(data: errData, encoding: .utf8) ?? ""
            }
            return ADBResult(exitCode: process.terminationStatus, output: output)
        }
        throw ConnectionError.refused
    }
}
