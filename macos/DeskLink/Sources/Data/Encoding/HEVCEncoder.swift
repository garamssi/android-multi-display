import Foundation
import VideoToolbox
import CoreVideo
import CoreMedia

/// HEVC hardware encoder using VideoToolbox.
public final class HEVCEncoder: VideoEncoding, @unchecked Sendable {
    private var session: VTCompressionSession?
    private var frameCounter: UInt32 = 0
    private var currentConfig: DisplayConfig?
    private var pendingForceKeyframe = false
    private let lock = NSLock()

    /// Codec-specific data (VPS + SPS + PPS) extracted from the first keyframe.
    public private(set) var codecConfigData: Data?

    public init() {}

    deinit {
        lock.withLock {
            if let session = session {
                VTCompressionSessionInvalidate(session)
            }
        }
    }

    public func configure(config: DisplayConfig) async throws {
        try lock.withLock {
            // Invalidate existing session
            if let existing = session {
                VTCompressionSessionInvalidate(existing)
                session = nil
            }

            let codecType: CMVideoCodecType = config.codec == .hevc
                ? kCMVideoCodecType_HEVC
                : kCMVideoCodecType_H264

            var newSession: VTCompressionSession?
            let status = VTCompressionSessionCreate(
                allocator: kCFAllocatorDefault,
                width: Int32(config.width),
                height: Int32(config.height),
                codecType: codecType,
                encoderSpecification: [
                    kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: true,
                ] as CFDictionary,
                imageBufferAttributes: [
                    kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                    kCVPixelBufferWidthKey: config.width,
                    kCVPixelBufferHeightKey: config.height,
                ] as CFDictionary,
                compressedDataAllocator: nil,
                outputCallback: nil,
                refcon: nil,
                compressionSessionOut: &newSession
            )

            guard status == noErr, let session = newSession else {
                throw ConnectionError.encoderInitFailed
            }

            // Configure session properties
            VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
            VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)

            let bitrate = config.bitrateKbps * 1000
            VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: bitrate as CFNumber)

            let bytesPerSecond = Double(bitrate) / 8.0
            let dataRateLimit = [bytesPerSecond * 1.5, 1.0] as CFArray
            VTSessionSetProperty(session, key: kVTCompressionPropertyKey_DataRateLimits, value: dataRateLimit)

            VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: config.fps * config.keyframeInterval as CFNumber)
            VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: config.fps as CFNumber)
            VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ProfileLevel,
                                 value: config.codec == .hevc
                                    ? kVTProfileLevel_HEVC_Main_AutoLevel
                                    : kVTProfileLevel_H264_Main_AutoLevel)

            VTCompressionSessionPrepareToEncodeFrames(session)

            self.session = session
            self.currentConfig = config
            self.frameCounter = 0
            self.codecConfigData = nil
        }
    }

    public func encode(frame: VideoFrame) async throws -> EncodedFrame {
        try lock.withLock {
            guard let session = session, let config = currentConfig else {
                throw ConnectionError.encoderFailed
            }

            // Create pixel buffer from raw BGRA data
            var pixelBuffer: CVPixelBuffer?
            let createStatus = CVPixelBufferCreate(
                kCFAllocatorDefault,
                config.width,
                config.height,
                kCVPixelFormatType_32BGRA,
                nil,
                &pixelBuffer
            )

            guard createStatus == kCVReturnSuccess, let buffer = pixelBuffer else {
                throw ConnectionError.encoderFailed
            }

            // Copy frame data into pixel buffer
            CVPixelBufferLockBaseAddress(buffer, [])
            if let baseAddress = CVPixelBufferGetBaseAddress(buffer) {
                let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
                let height = CVPixelBufferGetHeight(buffer)
                let bufferSize = bytesPerRow * height
                let copySize = min(frame.data.count, bufferSize)
                frame.data.withUnsafeBytes { rawBuffer in
                    baseAddress.copyMemory(from: rawBuffer.baseAddress!, byteCount: copySize)
                }
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])

            // Create presentation timestamp
            let pts = CMTimeMake(value: Int64(frameCounter), timescale: Int32(config.fps))

            // Check if a keyframe was requested
            var frameProperties: CFDictionary? = nil
            if pendingForceKeyframe {
                frameProperties = [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary
                pendingForceKeyframe = false
            }

            // Encode synchronously using encodeFrame with outputHandler
            var encodedData = Data()
            var isKeyframe = false
            var encodeError: Error?

            let semaphore = DispatchSemaphore(value: 0)

            let encodeStatus = VTCompressionSessionEncodeFrame(
                session,
                imageBuffer: buffer,
                presentationTimeStamp: pts,
                duration: CMTimeMake(value: 1, timescale: Int32(config.fps)),
                frameProperties: frameProperties,
                infoFlagsOut: nil
            ) { status, _, sampleBuffer in
                defer { semaphore.signal() }

                guard status == noErr, let sampleBuffer = sampleBuffer else {
                    encodeError = ConnectionError.encoderFailed
                    return
                }

                // Check if keyframe
                if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[CFString: Any]],
                   let first = attachments.first {
                    isKeyframe = !(first[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
                }

                // Extract NAL data
                guard let dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else {
                    encodeError = ConnectionError.encoderFailed
                    return
                }

                var length: Int = 0
                var dataPointer: UnsafeMutablePointer<Int8>?
                CMBlockBufferGetDataPointer(dataBuffer, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &dataPointer)

                if let dataPointer = dataPointer, length > 0 {
                    encodedData = Data(bytes: dataPointer, count: length)
                }

                // Extract codec config data from keyframes
                if isKeyframe {
                    if let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer) {
                        self.codecConfigData = Self.extractCodecConfig(from: formatDesc)
                    }
                }
            }

            guard encodeStatus == noErr else {
                throw ConnectionError.encoderFailed
            }

            semaphore.wait()

            if let error = encodeError {
                throw error
            }

            let result = EncodedFrame(
                data: encodedData,
                timestampUs: frame.timestampUs,
                isKeyframe: isKeyframe,
                frameNumber: frameCounter
            )
            frameCounter += 1
            return result
        }
    }

    public func updateBitrate(kbps: Int) async {
        lock.withLock {
            guard let session = session else { return }
            let bitrate = kbps * 1000
            VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: bitrate as CFNumber)
        }
    }

    public func forceKeyframe() async {
        lock.withLock {
            pendingForceKeyframe = true
        }
    }

    // MARK: - Private

    private static func extractCodecConfig(from formatDescription: CMFormatDescription) -> Data? {
        let mediaSubType = CMFormatDescriptionGetMediaSubType(formatDescription)

        if mediaSubType == kCMVideoCodecType_HEVC {
            return extractHEVCConfig(from: formatDescription)
        } else if mediaSubType == kCMVideoCodecType_H264 {
            return extractH264Config(from: formatDescription)
        }
        return nil
    }

    private static func extractHEVCConfig(from formatDescription: CMFormatDescription) -> Data? {
        var data = Data()

        // Extract VPS, SPS, PPS parameter sets
        var paramSetCount: Int = 0
        CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
            formatDescription, parameterSetIndex: 0,
            parameterSetPointerOut: nil, parameterSetSizeOut: nil,
            parameterSetCountOut: &paramSetCount, nalUnitHeaderLengthOut: nil
        )

        for i in 0..<paramSetCount {
            var paramSetPointer: UnsafePointer<UInt8>?
            var paramSetSize: Int = 0
            let status = CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                formatDescription, parameterSetIndex: i,
                parameterSetPointerOut: &paramSetPointer, parameterSetSizeOut: &paramSetSize,
                parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil
            )
            if status == noErr, let pointer = paramSetPointer {
                // Add Annex-B start code
                data.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
                data.append(pointer, count: paramSetSize)
            }
        }

        return data.isEmpty ? nil : data
    }

    private static func extractH264Config(from formatDescription: CMFormatDescription) -> Data? {
        var data = Data()

        // Extract SPS
        var spsPointer: UnsafePointer<UInt8>?
        var spsSize: Int = 0
        var paramCount: Int = 0
        CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            formatDescription, parameterSetIndex: 0,
            parameterSetPointerOut: &spsPointer, parameterSetSizeOut: &spsSize,
            parameterSetCountOut: &paramCount, nalUnitHeaderLengthOut: nil
        )
        if let sps = spsPointer {
            data.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
            data.append(sps, count: spsSize)
        }

        // Extract PPS
        var ppsPointer: UnsafePointer<UInt8>?
        var ppsSize: Int = 0
        CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            formatDescription, parameterSetIndex: 1,
            parameterSetPointerOut: &ppsPointer, parameterSetSizeOut: &ppsSize,
            parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil
        )
        if let pps = ppsPointer {
            data.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
            data.append(pps, count: ppsSize)
        }

        return data.isEmpty ? nil : data
    }
}
