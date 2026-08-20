import AudioToolbox
import CoreAudio
import Foundation

/// Captures all system audio through a Core Audio process tap and, while capturing,
/// keeps it out of the Mac's speakers.
///
/// Why a process tap rather than ScreenCaptureKit's `capturesAudio`: SCK copies the
/// audio but cannot suppress local playback, so the Mac and the tablet would both make
/// sound and the tablet's transport delay would be heard as an echo. A tap created with
/// `CATapMuteBehavior.mutedWhenTapped` routes the audio through us INSTEAD of the
/// output device for as long as we are reading it — which is exactly "play it on the
/// tablet, not here". Choosing `mutedWhenTapped` over `muted` is deliberate: it is
/// self-healing, so if this process stops reading (crash, stop, tablet unplugged) the
/// OS restores normal playback on its own.
///
/// This is an `actor` because the failure it guards against is severe. Two overlapping
/// `startCapture` calls would each create a tap, and the loser's tap would never be
/// destroyed — an orphaned tap whose IO proc keeps reading holds a permanent mute claim,
/// leaving the user with a silent Mac for the life of the process and defeating the
/// self-healing above. Serializing every transition through actor isolation makes that
/// interleaving impossible rather than unlikely.
@available(macOS 14.2, *)
public actor CoreAudioTapCapturer: AudioCapturing {

    /// Everything one live capture owns, so it can be torn down as a unit.
    private struct Session {
        let tapID: AudioObjectID
        let aggregateDeviceID: AudioObjectID
        let ioProcID: AudioDeviceIOProcID
        let continuation: AsyncStream<AudioChunk>.Continuation
        let consumerTask: Task<Void, Never>
        /// Watches for the default output device changing under the aggregate device.
        let deviceWatcherTask: Task<Void, Never>
    }

    /// Capacity of one ring-buffer slot. A Core Audio render quantum is typically 512
    /// sample frames but is not contractually bounded, so this leaves generous room:
    /// 8192 frames of 16-bit stereo. An oversized quantum is counted, never truncated.
    private static let slotCapacityBytes = 8192 * 2 * PCMConverter.bytesPerInt16Sample

    /// Slots in flight. At ~10 ms per quantum this is roughly a third of a second of
    /// slack for the consumer — enough to ride out a scheduling hiccup, small enough
    /// that a genuinely stalled consumer drops audio instead of accumulating latency the
    /// lip-sync path would then have to correct.
    private static let slotCount = 32

    /// Poll interval when the ring buffer is empty. A render quantum at 48 kHz is
    /// ~10 ms, so 2 ms adds negligible latency while keeping the task off the CPU.
    private static let idlePollIntervalNanos: UInt64 = 2_000_000

    /// How often the default output device is re-checked. A device switch is a
    /// human-scale event (plugging in headphones), so a second is responsive without
    /// polling the HAL needlessly.
    private static let deviceCheckIntervalNanos: UInt64 = 1_000_000_000

    private static let tapName = "DeskLink System Audio"
    private static let aggregateDeviceName = "DeskLink Audio Bridge"

    private var session: Session?

    /// Real-time-safe handoff from the IO proc to the consumer task. Reset between
    /// sessions so a new connection never receives the previous session's timestamps.
    private let ringBuffer = AudioChunkRingBuffer(
        slotCount: CoreAudioTapCapturer.slotCount,
        slotCapacityBytes: CoreAudioTapCapturer.slotCapacityBytes
    )

    /// Queue the IO proc block is dispatched on. Core Audio drives it with real-time
    /// priority; nothing on that path may allocate or block.
    private let ioQueue = DispatchQueue(label: "com.desklink.audio.tap", qos: .userInteractive)

    public init() {}

    // MARK: - AudioCapturing

    public func startCapture() async throws -> AudioCaptureSession {
        await stopCapture()
        // Stale slots would replay the previous session's timestamps into this one.
        ringBuffer.reset()

        let outputDeviceID = try Self.defaultOutputDeviceID()
        let outputDeviceUID = try Self.deviceUID(of: outputDeviceID)

        let tapUUID = UUID()
        let tapID = try Self.createSystemAudioTap(uuid: tapUUID)

        // From here on every failure must destroy the tap: an orphaned tap keeps the
        // Mac's speakers muted for the life of the process.
        var aggregateID = AudioObjectID(kAudioObjectUnknown)
        var procID: AudioDeviceIOProcID?
        do {
            let tapFormat = try Self.tapStreamFormat(tapID: tapID)
            let format = try Self.wireFormat(from: tapFormat)

            aggregateID = try Self.createAggregateDevice(
                tapUUID: tapUUID,
                outputDeviceUID: outputDeviceUID
            )
            try Self.verifyTapIsTheOnlyInput(of: aggregateID, matching: tapFormat)

            procID = try installIOProc(on: aggregateID)
            guard let procID else { throw AudioCaptureError.unexpectedNilResult(operation: "IOProcID") }

            let (stream, continuation) = AsyncStream<AudioChunk>.makeStream(
                bufferingPolicy: .bufferingNewest(Self.slotCount)
            )
            try Self.check(AudioDeviceStart(aggregateID, procID), "AudioDeviceStart")

            session = Session(
                tapID: tapID,
                aggregateDeviceID: aggregateID,
                ioProcID: procID,
                continuation: continuation,
                consumerTask: Self.startConsumer(
                    ringBuffer: ringBuffer,
                    format: format,
                    continuation: continuation,
                    idlePollIntervalNanos: Self.idlePollIntervalNanos
                ),
                deviceWatcherTask: Self.startDeviceWatcher(
                    initialDeviceID: outputDeviceID,
                    continuation: continuation
                )
            )
            Log.info(.capture, "audio tap started: \(format.sampleRate) Hz, \(format.channelCount) ch, Mac output muted")
            return AudioCaptureSession(format: format, chunks: stream)
        } catch {
            // `session` was never assigned, so nothing here can leave a destroyed ID
            // behind in actor state — the objects are released using the locals only.
            Self.releaseCoreAudioObjects(tapID: tapID, aggregateID: aggregateID, procID: procID)
            throw error
        }
    }

    public func stopCapture() async {
        guard let session else { return }
        self.session = nil

        session.deviceWatcherTask.cancel()
        session.consumerTask.cancel()
        // Awaited so the old consumer cannot still be draining slots when the next
        // session starts filling them; otherwise its chunks would be yielded into an
        // already-finished continuation and vanish without being counted as drops.
        await session.consumerTask.value
        session.continuation.finish()

        Self.releaseCoreAudioObjects(
            tapID: session.tapID,
            aggregateID: session.aggregateDeviceID,
            procID: session.ioProcID
        )

        let dropped = ringBuffer.droppedChunkCount
        if dropped > 0 {
            Log.error(.capture, "audio tap: \(dropped) chunk(s) dropped this session — consumer fell behind")
        }
    }

    // MARK: - Consumer

    /// Drains the ring buffer into the `AsyncStream`. Runs outside the real-time
    /// context, so allocating `Data` and awaiting are both fine here.
    private static func startConsumer(
        ringBuffer: AudioChunkRingBuffer,
        format: AudioFormat,
        continuation: AsyncStream<AudioChunk>.Continuation,
        idlePollIntervalNanos: UInt64
    ) -> Task<Void, Never> {
        Task.detached(priority: .userInitiated) {
            while !Task.isCancelled {
                var yieldedAny = false
                while let slot = ringBuffer.read() {
                    yieldedAny = true
                    continuation.yield(
                        AudioChunk(pcm: slot.pcm, timestampUs: slot.timestampUs, format: format)
                    )
                }
                if !yieldedAny {
                    try? await Task.sleep(nanoseconds: idlePollIntervalNanos)
                }
            }
            continuation.finish()
        }
    }

    /// Ends the chunk stream when the default output device changes.
    ///
    /// The aggregate device names the output device as its main sub-device, so plugging in
    /// headphones leaves the tap attached to a device that is no longer the one playing
    /// audio — and the sound stops with no error raised anywhere. Finishing the stream
    /// surfaces it as a normal end-of-stream, which makes the server's streaming loop tear
    /// the session down; the client reconnects and the capture is rebuilt against the new
    /// device. Rebuilding in place would mean re-deriving the format mid-stream, and a
    /// changed sample rate has to reach the client as a fresh AUDIO_CONFIG anyway.
    private static func startDeviceWatcher(
        initialDeviceID: AudioObjectID,
        continuation: AsyncStream<AudioChunk>.Continuation
    ) -> Task<Void, Never> {
        Task.detached(priority: .utility) {
            var watcher = DefaultOutputDeviceWatcher(currentDeviceID: initialDeviceID)
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: deviceCheckIntervalNanos)
                if Task.isCancelled { return }
                let deviceID = (try? defaultOutputDeviceID()) ?? DefaultOutputDeviceWatcher.unknownDeviceID
                if watcher.deviceDidChange(to: deviceID) {
                    Log.info(.capture, "audio tap: default output device changed; restarting capture")
                    continuation.finish()
                    return
                }
            }
        }
    }

    // MARK: - IO proc

    private func installIOProc(on deviceID: AudioObjectID) throws -> AudioDeviceIOProcID? {
        var procID: AudioDeviceIOProcID?
        let ringBuffer = self.ringBuffer

        // Real-time context: convert directly into the slot the ring buffer lends us.
        // No allocation, no temporary buffer, no Swift concurrency entry.
        let block: AudioDeviceIOBlock = { _, inputData, inputTime, _, _ in
            // Without this flag mHostTime is meaningless, and a garbage timestamp is a
            // destroyed lip-sync axis — so the quantum is dropped rather than mis-stamped.
            guard inputTime.pointee.mFlags.contains(.hostTimeValid) else { return }
            let timestampUs = MediaClock.microsFromHostTime(inputTime.pointee.mHostTime)

            let buffers = UnsafeMutableAudioBufferListPointer(
                UnsafeMutablePointer(mutating: inputData)
            )
            // `verifyTapIsTheOnlyInput` established that buffer 0 is the tap.
            guard let buffer = buffers.first, let source = buffer.mData else { return }
            let sampleCount = Int(buffer.mDataByteSize) / MemoryLayout<Float>.size
            guard sampleCount > 0 else { return }

            let floats = source.assumingMemoryBound(to: Float.self)
            let requiredBytes = sampleCount * PCMConverter.bytesPerInt16Sample
            guard requiredBytes <= ringBuffer.slotCapacityBytes else {
                // Refuse rather than truncate: a short chunk desyncs the frame count the
                // client's playout prediction depends on.
                ringBuffer.recordOversizedChunk()
                return
            }

            _ = ringBuffer.withWritableSlot(timestampUs: timestampUs) { destination, capacity in
                guard requiredBytes <= capacity else { return 0 }
                PCMConverter.writeInt16LittleEndian(
                    from: floats,
                    sampleCount: sampleCount,
                    into: destination
                )
                return requiredBytes
            }
        }

        try Self.check(
            AudioDeviceCreateIOProcIDWithBlock(&procID, deviceID, ioQueue, block),
            "AudioDeviceCreateIOProcIDWithBlock"
        )
        return procID
    }

    // MARK: - Core Audio setup

    private static func createSystemAudioTap(uuid: UUID) throws -> AudioObjectID {
        // Empty exclusion list = every process playing to the default output device.
        let description = CATapDescription(stereoGlobalTapButExcludeProcesses: [])
        description.name = tapName
        description.uuid = uuid
        // Not visible as a global audio object; it exists only for this app.
        description.isPrivate = true
        description.muteBehavior = .mutedWhenTapped

        var tapID = AudioObjectID(kAudioObjectUnknown)
        try check(AudioHardwareCreateProcessTap(description, &tapID), "AudioHardwareCreateProcessTap")
        guard tapID != AudioObjectID(kAudioObjectUnknown) else {
            throw AudioCaptureError.unexpectedNilResult(operation: "AudioHardwareCreateProcessTap")
        }
        return tapID
    }

    private static func createAggregateDevice(tapUUID: UUID, outputDeviceUID: String) throws -> AudioObjectID {
        let description: [String: Any] = [
            kAudioAggregateDeviceNameKey: aggregateDeviceName,
            kAudioAggregateDeviceUIDKey: UUID().uuidString,
            kAudioAggregateDeviceMainSubDeviceKey: outputDeviceUID,
            // Private: never appears in Sound settings, so the user's output selection
            // is untouched.
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceSubDeviceListKey: [
                [kAudioSubDeviceUIDKey: outputDeviceUID]
            ],
            kAudioAggregateDeviceTapListKey: [
                [
                    kAudioSubTapUIDKey: tapUUID.uuidString,
                    // Drift compensation: the tap and the output device are clocked
                    // independently, and uncorrected drift shows up as slowly growing
                    // lip-sync error.
                    kAudioSubTapDriftCompensationKey: true,
                ]
            ],
        ]

        var deviceID = AudioObjectID(kAudioObjectUnknown)
        try check(
            AudioHardwareCreateAggregateDevice(description as CFDictionary, &deviceID),
            "AudioHardwareCreateAggregateDevice"
        )
        return deviceID
    }

    /// Confirms that the aggregate device presents exactly ONE input stream and that it
    /// matches the tap's channel count.
    ///
    /// This is not defensive padding. The aggregate also contains the real output
    /// device, and output devices that carry input streams are common (USB headsets,
    /// audio interfaces, displays with a built-in mic). If such a device contributed a
    /// buffer, the IO proc's `buffers.first` could be the MICROPHONE rather than the
    /// tap — and the tablet would play the room instead of the Mac, at the wrong channel
    /// count. Failing loudly is the only acceptable outcome.
    private static func verifyTapIsTheOnlyInput(
        of deviceID: AudioObjectID,
        matching tapFormat: AudioStreamBasicDescription
    ) throws {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyStreamConfiguration,
            mScope: kAudioObjectPropertyScopeInput,
            mElement: kAudioObjectPropertyElementMain
        )
        var size: UInt32 = 0
        try check(
            AudioObjectGetPropertyDataSize(deviceID, &address, 0, nil, &size),
            "AudioObjectGetPropertyDataSize(InputStreamConfiguration)"
        )

        let bufferListMemory = UnsafeMutableRawPointer.allocate(
            byteCount: Int(size),
            alignment: MemoryLayout<AudioBufferList>.alignment
        )
        defer { bufferListMemory.deallocate() }
        try check(
            AudioObjectGetPropertyData(deviceID, &address, 0, nil, &size, bufferListMemory),
            "AudioObjectGetPropertyData(InputStreamConfiguration)"
        )

        let bufferList = UnsafeMutableAudioBufferListPointer(
            bufferListMemory.assumingMemoryBound(to: AudioBufferList.self)
        )
        let channelCounts = bufferList.map { $0.mNumberChannels }
        guard channelCounts.count == 1, channelCounts[0] == tapFormat.mChannelsPerFrame else {
            throw AudioCaptureError.ambiguousInputStreams(
                channelCountsPerBuffer: channelCounts.map(Int.init),
                expectedChannels: Int(tapFormat.mChannelsPerFrame)
            )
        }
    }

    private static func defaultOutputDeviceID() throws -> AudioObjectID {
        var deviceID = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        try check(
            AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &deviceID),
            "AudioObjectGetPropertyData(DefaultOutputDevice)"
        )
        guard deviceID != AudioObjectID(kAudioObjectUnknown) else {
            throw AudioCaptureError.noDefaultOutputDevice
        }
        return deviceID
    }

    private static func deviceUID(of deviceID: AudioObjectID) throws -> String {
        // Core Audio returns a retained CFString here (copy semantics), so it is read as
        // an `Unmanaged` reference and released by `takeRetainedValue`. Passing a
        // `CFString` variable's address directly would hand Core Audio a pointer to an
        // object reference and leak the result.
        var uidRef: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyDeviceUID,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        try check(
            AudioObjectGetPropertyData(deviceID, &address, 0, nil, &size, &uidRef),
            "AudioObjectGetPropertyData(DeviceUID)"
        )
        guard let uidRef else {
            throw AudioCaptureError.unexpectedNilResult(operation: "AudioObjectGetPropertyData(DeviceUID)")
        }
        return uidRef.takeRetainedValue() as String
    }

    private static func tapStreamFormat(tapID: AudioObjectID) throws -> AudioStreamBasicDescription {
        var format = AudioStreamBasicDescription()
        var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioTapPropertyFormat,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        try check(
            AudioObjectGetPropertyData(tapID, &address, 0, nil, &size, &format),
            "AudioObjectGetPropertyData(TapFormat)"
        )
        return format
    }

    /// Maps the tap's native layout onto the wire format, rejecting anything the IO
    /// proc's conversion loop does not actually handle. Failing loudly here beats
    /// shipping silently wrong audio: a non-interleaved or non-float tap would be
    /// reinterpreted as interleaved floats and come out as noise.
    static func wireFormat(from asbd: AudioStreamBasicDescription) throws -> AudioFormat {
        let isFloat = asbd.mFormatFlags & kAudioFormatFlagIsFloat != 0
        let isNonInterleaved = asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        guard asbd.mFormatID == kAudioFormatLinearPCM,
              isFloat,
              asbd.mBitsPerChannel == UInt32(MemoryLayout<Float>.size * 8),
              !isNonInterleaved,
              asbd.mChannelsPerFrame > 0,
              asbd.mSampleRate > 0,
              let format = AudioFormat(
                  sampleRate: UInt32(asbd.mSampleRate),
                  channelCount: UInt8(asbd.mChannelsPerFrame),
                  encoding: .pcmSignedLittleEndian16
              )
        else {
            throw AudioCaptureError.unsupportedTapFormat(
                description: "id=\(asbd.mFormatID) flags=\(asbd.mFormatFlags) "
                    + "bits=\(asbd.mBitsPerChannel) ch=\(asbd.mChannelsPerFrame) rate=\(asbd.mSampleRate)"
            )
        }
        return format
    }

    // MARK: - Teardown

    /// Releases the Core Audio objects in reverse creation order. Destroying the tap is
    /// not optional housekeeping: an orphaned tap keeps a mute claim on the user's audio.
    private static func releaseCoreAudioObjects(
        tapID: AudioObjectID,
        aggregateID: AudioObjectID,
        procID: AudioDeviceIOProcID?
    ) {
        if aggregateID != AudioObjectID(kAudioObjectUnknown) {
            if let procID {
                AudioDeviceStop(aggregateID, procID)
                AudioDeviceDestroyIOProcID(aggregateID, procID)
            }
            AudioHardwareDestroyAggregateDevice(aggregateID)
        }
        if tapID != AudioObjectID(kAudioObjectUnknown) {
            AudioHardwareDestroyProcessTap(tapID)
        }
    }

    private static func check(_ status: OSStatus, _ operation: String) throws {
        guard status == noErr else {
            throw AudioCaptureError.coreAudioFailure(operation: operation, status: status)
        }
    }
}
