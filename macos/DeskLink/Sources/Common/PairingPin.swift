import Foundation

enum PairingPin {

    static let defaultsKey = "pairingPin"
    static let generatedAtKey = "pairingPinGeneratedAt"

    static let lifetime: TimeInterval = 60

    static var current: String {
        if let existing = UserDefaults.standard.string(forKey: defaultsKey),
           existing.count == ProtocolConstants.pairingPinLength,
           existing.allSatisfy(\.isNumber) {
            return existing
        }
        return regenerate()
    }

    static var generatedAt: Date? {
        let stored = UserDefaults.standard.double(forKey: generatedAtKey)
        return stored > 0 ? Date(timeIntervalSince1970: stored) : nil
    }

    @discardableResult
    static func regenerate(now: Date = Date()) -> String {
        let pin = (0 ..< ProtocolConstants.pairingPinLength)
            .map { _ in String(Int.random(in: 0 ... 9)) }
            .joined()
        UserDefaults.standard.set(pin, forKey: defaultsKey)
        UserDefaults.standard.set(now.timeIntervalSince1970, forKey: generatedAtKey)
        return pin
    }

    static func isExpired(now: Date = Date()) -> Bool {
        guard let generatedAt else { return true }
        return now.timeIntervalSince(generatedAt) >= lifetime
    }

    static func secondsRemaining(now: Date = Date()) -> Int {
        guard let generatedAt else { return 0 }
        let remaining = lifetime - now.timeIntervalSince(generatedAt)
        return max(0, Int(remaining.rounded(.up)))
    }

    @discardableResult
    static func rotateIfExpired(now: Date = Date()) -> String {
        if isExpired(now: now) { return regenerate(now: now) }
        return current
    }
}
