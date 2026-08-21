import Foundation

// Parses `adb devices` output.
//
// Split out as pure logic because device selection is a correctness issue, not a
// formality: with more than one target `adb reverse` fails outright, so the count matters
// as much as the presence of any device.
enum ADBDeviceList {

    // Serials of attached, authorized devices. Lines for unauthorized, offline, or
    // still-booting targets are excluded: reversing to one of those fails.
    static func attachedSerials(fromDevicesOutput output: String) -> [String] {
        output
            .split(separator: "\n")
            .compactMap { line -> String? in
                let fields = line.split(separator: "\t", omittingEmptySubsequences: true)
                guard fields.count >= 2 else { return nil }
                let serial = fields[0].trimmingCharacters(in: .whitespaces)
                let state = fields[1].trimmingCharacters(in: .whitespaces)
                guard state == attachedState, !serial.isEmpty else { return nil }
                return serial
            }
    }

    // The only state in which a device accepts `adb reverse`.
    private static let attachedState = "device"
}
