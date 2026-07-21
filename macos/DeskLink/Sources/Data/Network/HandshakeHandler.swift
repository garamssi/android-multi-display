import Foundation

/// Handles the control channel handshake protocol:
/// 1. Receive HANDSHAKE_REQUEST from client
/// 2. Send HANDSHAKE_RESPONSE
/// 3. Receive CONFIG_REQUEST
/// 4. Send CONFIG_RESPONSE with negotiated settings
/// 5. Send START_STREAM when ready
public final class HandshakeHandler: Sendable {
    public init() {}

    // MARK: - Server-side handshake

    public enum HandshakeResult {
        case accepted(response: Data, clientInfo: ClientInfo)
        case rejected(response: Data, error: ConnectionError)
    }

    public func handleHandshakeRequest(payload: Data) -> HandshakeResult {
        guard let json = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] else {
            let response = makeHandshakeResponse(accepted: false, reason: "Invalid JSON payload")
            return .rejected(response: response, error: .protocolMismatch)
        }

        guard let version = json["protocolVersion"] as? Int,
              version == ProtocolConstants.protocolVersion else {
            let response = makeHandshakeResponse(accepted: false, reason: "Protocol version mismatch")
            return .rejected(response: response, error: .protocolMismatch)
        }

        let clientInfo = ClientInfo(
            clientName: json["clientName"] as? String ?? "Unknown",
            clientVersion: json["clientVersion"] as? String ?? "0.0.0",
            deviceModel: json["deviceModel"] as? String ?? "Unknown",
            screenWidth: json["screenWidth"] as? Int ?? 1920,
            screenHeight: json["screenHeight"] as? Int ?? 1200,
            maxFps: json["maxFps"] as? Int ?? 60,
            supportedCodecs: (json["supportedCodecs"] as? [String]) ?? ["hevc"],
            multiTouchMaxPoints: json["multiTouchMaxPoints"] as? Int ?? 10
        )

        let response = makeHandshakeResponse(accepted: true, reason: nil)
        return .accepted(response: response, clientInfo: clientInfo)
    }

    public func handleConfigRequest(payload: Data, clientInfo: ClientInfo) throws -> (response: Data, config: DisplayConfig) {
        guard let json = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] else {
            throw ConnectionError.configNegotiationFailed
        }

        let requestedWidth = json["width"] as? Int ?? 1920
        let requestedHeight = json["height"] as? Int ?? 1200
        let requestedFps = json["fps"] as? Int ?? 60
        let requestedCodec = (json["codec"] as? String ?? "hevc").lowercased()
        let requestedBitrate = json["bitrateKbps"] as? Int ?? 20_000

        // S-M5: reject zero/invalid resolution (CONFIG_INVALID / 1400) before
        // negotiating. A non-positive dimension cannot produce a valid display.
        guard requestedWidth > 0, requestedHeight > 0 else {
            throw ConnectionError.configInvalid
        }

        // S-M4: validate the requested codec against the client's advertised
        // supportedCodecs. Reject unknown or unsupported codecs
        // (CODEC_NOT_SUPPORTED / 1104).
        let supported = Set(clientInfo.supportedCodecs.map { $0.lowercased() })
        guard requestedCodec == "hevc" || requestedCodec == "h264" else {
            throw ConnectionError.codecNotSupported
        }
        guard supported.contains(requestedCodec) else {
            throw ConnectionError.codecNotSupported
        }

        // Negotiate: clamp to the tablet panel. The requested resolution may be in
        // either orientation (a portrait request carries height > width), so clamp the
        // long/short edges against the panel's long/short edges and then restore the
        // requested orientation. A naive per-axis min() would shrink a portrait
        // request's height down to the panel's landscape height and distort it.
        let panelLong = max(clientInfo.screenWidth, clientInfo.screenHeight)
        let panelShort = min(clientInfo.screenWidth, clientInfo.screenHeight)
        let requestedLong = max(requestedWidth, requestedHeight)
        let requestedShort = min(requestedWidth, requestedHeight)
        let clampedLong = min(requestedLong, panelLong)
        let clampedShort = min(requestedShort, panelShort)
        let isPortrait = requestedHeight > requestedWidth
        let width = isPortrait ? clampedShort : clampedLong
        let height = isPortrait ? clampedLong : clampedShort
        let fps = min(requestedFps, max(1, clientInfo.maxFps))
        let codec: DisplayConfig.Codec = requestedCodec == "h264" ? .h264 : .hevc
        let bitrate = max(1000, min(requestedBitrate, 40_000))

        // Guard against a clamp that would zero out a dimension (e.g. client
        // advertised a non-positive screen size).
        guard width > 0, height > 0, fps > 0 else {
            throw ConnectionError.configInvalid
        }

        let config = DisplayConfig(
            width: width,
            height: height,
            fps: fps,
            codec: codec,
            bitrateKbps: bitrate
        )

        let response = makeConfigResponse(config: config)
        return (response, config)
    }

    /// Builds an ERROR (0x09) payload for the given connection error, per spec §3.10.
    public func makeErrorMessage(_ error: ConnectionError, message: String? = nil) -> Data {
        let json: [String: Any] = [
            "code": error.rawValue,
            "message": message ?? Self.defaultMessage(for: error),
        ]
        return (try? JSONSerialization.data(withJSONObject: json)) ?? Data()
    }

    private static func defaultMessage(for error: ConnectionError) -> String {
        switch error {
        case .refused: return "Connection refused"
        case .protocolMismatch: return "Protocol version mismatch"
        case .timeout: return "Operation timed out"
        case .lost: return "Connection lost"
        case .encoderInitFailed: return "Encoder failed to initialize"
        case .encoderFailed: return "Encoding failed"
        case .decoderInitFailed: return "Decoder failed to initialize"
        case .decoderFailed: return "Decoding failed"
        case .codecNotSupported: return "Requested codec is not supported"
        case .displayCreateFailed: return "Failed to create virtual display"
        case .displayCaptureFailed: return "Screen capture failed"
        case .displayResolutionInvalid: return "Invalid display resolution"
        case .inputInjectionFailed: return "Input injection failed"
        case .inputPermissionDenied: return "Input permission denied"
        case .configInvalid: return "Invalid configuration value"
        case .configNegotiationFailed: return "Configuration negotiation failed"
        }
    }

    public func makeStartStreamMessage() -> Data {
        // START_STREAM has no payload
        return Data()
    }

    // MARK: - Message builders

    private func makeHandshakeResponse(accepted: Bool, reason: String?) -> Data {
        var json: [String: Any] = [
            "protocolVersion": ProtocolConstants.protocolVersion,
            "accepted": accepted,
            "serverName": "DeskLink Mac",
            "serverVersion": "1.0.0",
            "osVersion": ProcessInfo.processInfo.operatingSystemVersionString,
        ]
        if let reason = reason {
            json["rejectReason"] = reason
        }
        return (try? JSONSerialization.data(withJSONObject: json)) ?? Data()
    }

    private func makeConfigResponse(config: DisplayConfig) -> Data {
        let json: [String: Any] = [
            "accepted": true,
            "width": config.width,
            "height": config.height,
            "fps": config.fps,
            "codec": config.codec == .hevc ? "hevc" : "h264",
            "bitrateKbps": config.bitrateKbps,
            "keyframeInterval": config.keyframeInterval,
        ]
        return (try? JSONSerialization.data(withJSONObject: json)) ?? Data()
    }
}

// MARK: - Client Info

public struct ClientInfo: Sendable {
    public let clientName: String
    public let clientVersion: String
    public let deviceModel: String
    public let screenWidth: Int
    public let screenHeight: Int
    public let maxFps: Int
    public let supportedCodecs: [String]
    public let multiTouchMaxPoints: Int
}
