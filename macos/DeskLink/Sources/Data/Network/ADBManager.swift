import Foundation

/// Manages ADB reverse tunneling for USB (localhost) connection mode.
///
/// The Mac is the **server** (listens on 7100–7102) and the Android device is the
/// **client** that dials `127.0.0.1:<port>`. To make the device's localhost reach
/// the Mac we use `adb reverse tcp:<devicePort> tcp:<hostPort>` (device → host),
/// NOT `adb forward` (which tunnels host → device and is for device-hosted servers).
///
/// - S-H1: the device-status `AsyncStream` and its continuation are created once
///   in `init` and stored, so every `deviceStatusChanges` access returns the same
///   live stream instead of overwriting the continuation.
/// - S-H3: `runADB` reads stdout/stderr fully to EOF *before* `waitUntilExit` to
///   avoid the classic pipe-buffer deadlock (a chatty child filling the 64KB pipe
///   while the parent waits on exit), and runs the blocking work on a dedicated
///   `DispatchQueue` via `withCheckedThrowingContinuation` so the cooperative pool
///   is never blocked.
public final class ADBManager: ADBManaging, @unchecked Sendable {
    private let lock = NSLock()
    private var isForwarding = false

    private let statusStream: AsyncStream<Bool>
    private let statusContinuation: AsyncStream<Bool>.Continuation

    /// Dedicated queue for blocking `Process` I/O and wait, off the cooperative pool.
    private let adbQueue = DispatchQueue(label: "com.desklink.adb", qos: .utility)

    /// `devicePort` is where the Android client dials on `127.0.0.1`; `hostPort` is
    /// the Mac server's listening port. `adb reverse tcp:devicePort tcp:hostPort`.
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
            // adb reverse tcp:<devicePort> tcp:<hostPort> — device localhost → Mac server.
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
        // A connected device line looks like: "<serial>\tdevice"
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

    /// Runs adb synchronously on the dedicated queue. Drains stdout+stderr to EOF
    /// before waiting for exit to avoid pipe-buffer deadlock.
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

            // Drain both pipes to EOF BEFORE waitUntilExit. readDataToEndOfFile
            // blocks until the write end closes (child exits), which prevents the
            // child from stalling on a full pipe while we wait on exit.
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
