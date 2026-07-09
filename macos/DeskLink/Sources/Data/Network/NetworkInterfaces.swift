import Foundation

/// Resolves the Mac's local-network IPv4 addresses so the Settings window can show the
/// user which address to type into the tablet for a manual LAN connection (there is no
/// discovery in this phase). Best-effort: returns the non-loopback IPv4 addresses of
/// interfaces that are currently up.
enum NetworkInterfaces {

    /// Non-loopback IPv4 addresses currently assigned to active interfaces.
    static func localIPv4Addresses() -> [String] {
        var addresses: [String] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0 else { return [] }
        defer { freeifaddrs(head) }

        var cursor = head
        while let ptr = cursor {
            let entry = ptr.pointee
            if let address = ipv4Address(of: entry) {
                addresses.append(address)
            }
            cursor = entry.ifa_next
        }
        return addresses
    }

    // MARK: - Private

    private static func ipv4Address(of entry: ifaddrs) -> String? {
        guard let sockaddrPtr = entry.ifa_addr else { return nil }
        guard sockaddrPtr.pointee.sa_family == UInt8(AF_INET) else { return nil }

        let flags = Int32(bitPattern: entry.ifa_flags)
        // Only interfaces that are up, and never loopback (we want reachable LAN IPs).
        guard flags & IFF_UP == IFF_UP else { return nil }
        guard flags & IFF_LOOPBACK == 0 else { return nil }

        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let result = getnameinfo(
            sockaddrPtr,
            socklen_t(sockaddrPtr.pointee.sa_len),
            &host,
            socklen_t(host.count),
            nil,
            0,
            NI_NUMERICHOST
        )
        guard result == 0 else { return nil }
        // getnameinfo writes a null-terminated numeric host; decode up to the null.
        // (String(cString: [CChar]) is deprecated — decode the UTF-8 bytes instead.)
        let bytes = host.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }
        let address = String(decoding: bytes, as: UTF8.self)
        return address.isEmpty ? nil : address
    }
}
