import Foundation
import VideoToolbox
import CoreVideo
import CoreMedia

public final class HEVCEncoder: VideoEncoding, @unchecked Sendable {
    private let lock = NSLock()
    private var session: VTCompressionSession?
    private var frameCounter: UInt32 = 0
    private var currentConfig: DisplayConfig?
    private var pendingForceKeyframe = false
    private var _codecConfigData: Data?

    public var codecConfigData: Data? {
        get async { lock.withLock { _codecConfigData } }
    }

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
            self._codecConfigData = nil
        }
    }

    public func encode(frame: VideoFrame) async throws -> EncodedFrame {
        let snapshot: (session: VTCompressionSession, config: DisplayConfig, frameNumber: UInt32, forceKeyframe: Bool)? = lock.withLock {
            guard let session = session, let config = currentConfig else { return nil }
            let snap = (session, config, frameCounter, pendingForceKeyframe)
            pendingForceKeyframe = false
            return snap
        }

        guard let snapshot else {
            throw ConnectionError.encoderFailed
        }

        // S-H4: run the blocking encode + semaphore.wait() with the lock RELEASED (holding it across the wait stalls updateBitrate/forceKeyframe); callers are already serialized by the pipeline's for-await loop.
        let output = try Self.performEncode(
            session: snapshot.session,
            config: snapshot.config,
            frame: frame,
            frameNumber: snapshot.frameNumber,
            forceKeyframe: snapshot.forceKeyframe
        )

        lock.withLock {
            if let csd = output.codecConfigData {
                _codecConfigData = csd
            }
            frameCounter &+= 1
        }

        return EncodedFrame(
            data: output.annexBData,
            timestampUs: frame.timestampUs,
            isKeyframe: output.isKeyframe,
            frameNumber: snapshot.frameNumber
        )
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

    // MARK: - Blocking encode (lock NOT held during the call)

    private struct RawEncodeOutput {
        let annexBData: Data
        let isKeyframe: Bool
        let codecConfigData: Data?
    }

    // Shared with the VideoToolbox completion handler; the handler completes before semaphore.wait() returns (happens-before), so access is race-free. @unchecked Sendable expresses that to Swift 6.
    private final class EncodeScratch: @unchecked Sendable {
        var rawAVCC = Data()
        var isKeyframe = false
        var csd: Data?
        var error: Error?
    }

    private static func performEncode(
        session: VTCompressionSession,
        config: DisplayConfig,
        frame: VideoFrame,
        frameNumber: UInt32,
        forceKeyframe: Bool
    ) throws -> RawEncodeOutput {
        let pbWidth = frame.width > 0 ? frame.width : config.width
        let pbHeight = frame.height > 0 ? frame.height : config.height
        var pixelBuffer: CVPixelBuffer?
        let createStatus = CVPixelBufferCreate(
            kCFAllocatorDefault,
            pbWidth,
            pbHeight,
            kCVPixelFormatType_32BGRA,
            nil,
            &pixelBuffer
        )

        guard createStatus == kCVReturnSuccess, let buffer = pixelBuffer else {
            throw ConnectionError.encoderFailed
        }

        // Copy BGRA rows honoring BOTH source and destination strides; a flat memcpy skews/duplicates the image whenever the strides or sizes differ.
        CVPixelBufferLockBaseAddress(buffer, [])
        if let baseAddress = CVPixelBufferGetBaseAddress(buffer) {
            let dstBytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
            let srcBytesPerRow = frame.bytesPerRow > 0 ? frame.bytesPerRow : pbWidth * 4
            let rowBytes = min(srcBytesPerRow, dstBytesPerRow, pbWidth * 4)
            frame.data.withUnsafeBytes { rawBuffer in
                if let src = rawBuffer.baseAddress, srcBytesPerRow > 0 {
                    let maxRows = min(pbHeight, frame.data.count / srcBytesPerRow)
                    for row in 0..<maxRows {
                        memcpy(
                            baseAddress + row * dstBytesPerRow,
                            src + row * srcBytesPerRow,
                            rowBytes
                        )
                    }
                }
            }
        }
        CVPixelBufferUnlockBaseAddress(buffer, [])

        let pts = CMTimeMake(value: Int64(frameNumber), timescale: Int32(config.fps))

        var frameProperties: CFDictionary? = nil
        if forceKeyframe {
            frameProperties = [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary
        }

        let scratch = EncodeScratch()
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
                scratch.error = ConnectionError.encoderFailed
                return
            }

            if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[CFString: Any]],
               let first = attachments.first {
                scratch.isKeyframe = !(first[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
            }

            guard let dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else {
                scratch.error = ConnectionError.encoderFailed
                return
            }

            var length: Int = 0
            var dataPointer: UnsafeMutablePointer<Int8>?
            CMBlockBufferGetDataPointer(dataBuffer, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &dataPointer)

            if let dataPointer = dataPointer, length > 0 {
                scratch.rawAVCC = Data(bytes: dataPointer, count: length)
            }

            if scratch.isKeyframe, let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer) {
                scratch.csd = extractCodecConfig(from: formatDesc)
            }
        }

        guard encodeStatus == noErr else {
            throw ConnectionError.encoderFailed
        }

        semaphore.wait()

        if let error = scratch.error {
            throw error
        }

        let annexB = (try? AnnexBConverter.convert(avcc: scratch.rawAVCC, lengthSize: 4)) ?? scratch.rawAVCC

        return RawEncodeOutput(annexBData: annexB, isKeyframe: scratch.isKeyframe, codecConfigData: scratch.csd)
    }

    // MARK: - CSD extraction (Annex-B)

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
                data.append(contentsOf: AnnexBConverter.startCode)
                data.append(pointer, count: paramSetSize)
            }
        }

        return data.isEmpty ? nil : data
    }

    private static func extractH264Config(from formatDescription: CMFormatDescription) -> Data? {
        var data = Data()

        var paramSetCount: Int = 0
        CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            formatDescription, parameterSetIndex: 0,
            parameterSetPointerOut: nil, parameterSetSizeOut: nil,
            parameterSetCountOut: &paramSetCount, nalUnitHeaderLengthOut: nil
        )

        for i in 0..<paramSetCount {
            var pointer: UnsafePointer<UInt8>?
            var size: Int = 0
            let status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                formatDescription, parameterSetIndex: i,
                parameterSetPointerOut: &pointer, parameterSetSizeOut: &size,
                parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil
            )
            if status == noErr, let pointer = pointer {
                data.append(contentsOf: AnnexBConverter.startCode)
                data.append(pointer, count: size)
            }
        }

        return data.isEmpty ? nil : data
    }
}
