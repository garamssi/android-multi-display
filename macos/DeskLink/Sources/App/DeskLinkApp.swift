import SwiftUI

@main
struct DeskLinkApp: App {
    var body: some Scene {
        MenuBarExtra("DeskLink", systemImage: "display.2") {
            StatusMenuView()
        }
        .menuBarExtraStyle(.window)
    }
}
