import SwiftUI

@main
struct DeskLinkApp: App {
    /// Single source of truth for the menu-bar UI; owns the `ServerCoordinator`.
    @State private var viewModel = ServerViewModel()

    /// Owns the Settings window's state (permissions + diagnostics), so the window
    /// keeps its state across open/close.
    @State private var settingsViewModel = SettingsViewModel()

    init() {
        // Register the bundled IBM Plex faces once at launch. If this fails (e.g.
        // the resource bundle is missing), the Font helpers fall back to the system
        // font, so the UI still renders.
        PlexFonts.register()
    }

    var body: some Scene {
        MenuBarExtra {
            StatusMenuView(viewModel: viewModel)
        } label: {
            Image(systemName: "display")
        }
        .menuBarExtraStyle(.window)

        // Dedicated Settings window, opened from the menu popover's "Settings…" row.
        Window("DeskLink Settings", id: SettingsWindowID.value) {
            SettingsView(viewModel: settingsViewModel)
        }
        // Size the window to its content so it grows/shrinks when the log viewer is
        // toggled, instead of leaving a fixed window with the content floating in it.
        .windowResizability(.contentSize)
    }
}
