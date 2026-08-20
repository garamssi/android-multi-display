import CoreAudio

/// Tracks which output device the tap's aggregate device was built around.
///
/// Why this is needed: the aggregate device names the default output device as its main
/// sub-device. When the user plugs in headphones or connects Bluetooth earbuds, the
/// default output changes and that sub-device is no longer the right one — the audio
/// stops with no error surfacing anywhere, which reads to the user as "the tablet just
/// went quiet". Detecting the change lets the capture be rebuilt against the new device.
///
/// Pure state, so the decision is testable without switching real hardware.
struct DefaultOutputDeviceWatcher {

    /// Core Audio's "no such object" value, which a poll can transiently return while a
    /// device switch is in progress.
    static let unknownDeviceID = AudioObjectID(kAudioObjectUnknown)

    private var currentDeviceID: AudioObjectID

    init(currentDeviceID: AudioObjectID) {
        self.currentDeviceID = currentDeviceID
    }

    /// Whether `deviceID` is a different output device than the one in use.
    ///
    /// A transient unknown reading is ignored rather than reported: rebuilding against no
    /// device would just fail, and the real device shows up on a later poll.
    mutating func deviceDidChange(to deviceID: AudioObjectID) -> Bool {
        guard deviceID != Self.unknownDeviceID else { return false }
        guard deviceID != currentDeviceID else { return false }
        // Adopt it immediately, or every later poll would report the same change and
        // restart the capture in a loop.
        currentDeviceID = deviceID
        return true
    }
}
