import Foundation

// Transport is `adb reverse` (device -> host); never `adb forward`.
// S-H1: status AsyncStream + continuation are created once in init and stored; do not rebuild per access (overwrites the continuation and drops subscribers).
public final class ADBManager: ADBManaging, @unchecked Sendable {
    private let lock = NSLock()
    private var isForwarding = false
    private var didLogProbeFailure = false

    private let statusStream: AsyncStream<Bool>
    private let statusContinuation: AsyncStream<Bool>.Continuation

    private let adbQueue = DispatchQueue(label: "com.desklink.adb", qos: .utility)

    private let ports: [(devicePort: UInt16, hostPort: UInt16)] = [
        (ProtocolConstants.portControl, ProtocolConstants.portControl),
        (ProtocolConstants.portVideo, ProtocolConstants.portVideo),
        (ProtocolConstants.portInput, ProtocolConstants.portInput),
        (ProtocolConstants.portAudio, ProtocolConstants.portAudio),
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
        let result: ADBResult
        do {
            result = try await runADB("devices")
        } catch {
            // "no device" and "cannot ask" are different states; the watcher polls once a
            // second, so report a persistent failure once rather than flooding the log.
            logProbeFailureOnce(error)
            return false
        }
        clearProbeFailureLog()
        let lines = result.output.split(separator: "\n")
        return lines.contains { line in
            line.contains("\tdevice") && !line.contains("List of")
        }
    }

    private func logProbeFailureOnce(_ error: Error) {
        let shouldLog = lock.withLock {
            guard !didLogProbeFailure else { return false }
            didLogProbeFailure = true
            return true
        }
        guard shouldLog else { return }
        Log.error(.adb, "cannot query devices: \(error)")
    }

    private func clearProbeFailureLog() {
        lock.withLock { didLogProbeFailure = false }
    }

    // MARK: - Private

    private struct ADBResult {
        let exitCode: Int32
        let output: String
    }

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
        guard let adbPath = ADBLocator.resolve() else {
            throw ADBError.executableNotFound(
                searched: ADBLocator.candidatePaths(
                    environment: ProcessInfo.processInfo.environment,
                    homeDirectory: NSHomeDirectory()
                )
            )
        }

        let process = Process()
        let stdoutPipe = Pipe()
        let stderrPipe = Pipe()
        process.executableURL = URL(fileURLWithPath: adbPath)
        process.arguments = arguments
        process.standardOutput = stdoutPipe
        process.standardError = stderrPipe

        try process.run()

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
}
