import Foundation

enum TransportSettings {

    static let wifiEnabledDefaultsKey = "transportWifiEnabled"

    static var wifiEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: wifiEnabledDefaultsKey) }
        set { UserDefaults.standard.set(newValue, forKey: wifiEnabledDefaultsKey) }
    }
}
