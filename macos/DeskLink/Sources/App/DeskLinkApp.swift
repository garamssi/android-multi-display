import SwiftUI

@main
struct DeskLinkApp: App {
    @State private var viewModel = ServerViewModel()

    @State private var settingsViewModel = SettingsViewModel()

    init() {
        PlexFonts.register()
    }

    var body: some Scene {
        MenuBarExtra {
            StatusMenuView(viewModel: viewModel)
        } label: {
            Image(systemName: "display")
        }
        .menuBarExtraStyle(.window)

        Window("DeskLink Settings", id: SettingsWindowID.value) {
            SettingsView(viewModel: settingsViewModel, serverViewModel: viewModel)
        }
        .windowResizability(.contentSize)
    }
}
