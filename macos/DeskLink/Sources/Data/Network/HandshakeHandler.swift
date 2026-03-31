import Foundation

/// Handles the control channel handshake protocol:
/// 1. Receive HANDSHAKE_REQUEST from client
/// 2. Send HANDSHAKE_RESPONSE
/// 3. Receive CONFIG_REQUEST
/// 4. Send CONFIG_RESPONSE with negotiated settings
/// 5. Send START_STREAM when ready
public final class HandshakeHandler: @unchecked Sendable {
    private let lock = NSLock()

    public init() {}

    // MARK: - Server-side handshake

    public func handleHandshakeRequest(payload: Data) throws -> (response: Data, clientInfo: ClientInfo) {
        guard let json = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] else {
            throw ConnectionError.protocolMismatch
        }

        guard let version = json["protocolVersion"] as? Int,
              version == ProtocolConstants.protocolVersion else {
            let response = makeHandshakeResponse(accepted: false, reason: "Protocol version mismatch")
            throw ConnectionError.protocolMismatch
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
        return (response, clientInfo)
    }

    public func handleConfigRequest(payload: Data, clientInfo: ClientInfo) throws -> (response: Data, config: DisplayConfig) {
        guard let json = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] else {
            throw ConnectionError.configNegotiationFailed
        }

        let requestedWidth = json["width"] as? Int ?? 1920
        let requestedHeight = json["height"] as? Int ?? 1200
        let requestedFps = json["fps"] as? Int ?? 60
        let requestedCodec = json["codec"] as? String ?? "hevc"
        let requestedBitrate = json["bitrateKbps"] as? Int ?? 20_000

        // Negotiate: clamp to supported ranges
        let width = min(requestedWidth, clientInfo.screenWidth)
        let height = min(requestedHeight, clientInfo.screenHeight)
        let fps = min(requestedFps, clientInfo.maxFps)
        let codec: DisplayConfig.Codec = requestedCodec == "h264" ? .h264 : .hevc
        let bitrate = max(1000, min(requestedBitrate, 40_000))

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
