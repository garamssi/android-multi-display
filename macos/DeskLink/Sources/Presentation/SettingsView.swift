import SwiftUI

/// Stable identifier for the Settings `Window` scene, opened from the menu popover.
enum SettingsWindowID {
    static let value = "settings"
}

/// The Settings window: macOS permission status/actions and diagnostic logging.
/// Pure renderer over `SettingsViewModel`; all platform work lives in the view-model.
@MainActor
struct SettingsView: View {
    let viewModel: SettingsViewModel

    /// The log viewer is hidden by default; the user opts in via "Show log viewer".
    /// As view `@State` it resets to hidden each time the window is (re)opened.
    @State private var showLogs = false

    private enum PermissionKind { case accessibility, screenRecording }

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Text("DeskLink Settings")
                .font(.plexSans(size: 18, weight: .semibold))
                .foregroundStyle(DesignTokens.textPrimary)

            permissionsSection
            connectionSection
            diagnosticsSection
            if showLogs {
                logViewer
            }
        }
        .padding(22)
        // The window tracks this content size (see `.windowResizability(.contentSize)`),
        // so the window itself grows when the log viewer is revealed and shrinks when it
        // is hidden — no floating content in an oversized window. Only the width is set;
        // height follows the content (the log panel has a fixed height above).
        .frame(width: showLogs ? 820 : 480, alignment: .topLeading)
        .background(DesignTokens.panelGradient, ignoresSafeAreaEdges: .all)
        .task {
            // Poll the permission states while open so a change made in System Settings
            // is reflected here within ~1.5s. Cancelled automatically on close.
            while !Task.isCancelled {
                viewModel.refresh()
                try? await Task.sleep(nanoseconds: 1_500_000_000)
            }
        }
        .onChange(of: showLogs) { _, isShown in
            // Load logs only when the user reveals the viewer.
            if isShown { viewModel.refreshLogs() }
        }
    }

    // MARK: - Permissions

    private var permissionsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("Permissions")
            permissionRow(.accessibility)
            permissionRow(.screenRecording)
        }
    }

    private func permissionRow(_ kind: PermissionKind) -> some View {
        let title: String
        let subtitle: String
        let granted: Bool
        switch kind {
        case .accessibility:
            title = "Accessibility"
            subtitle = "Send tablet touches as mouse input"
            granted = viewModel.accessibilityGranted
        case .screenRecording:
            title = "Screen Recording"
            subtitle = "Capture the Mac screen to stream"
            granted = viewModel.screenRecordingGranted
        }

        return HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(title).font(.plexSans(size: 14, weight: .medium))
                        .foregroundStyle(DesignTokens.textPrimary)
                    statusBadge(granted: granted)
                }
                Text(subtitle).font(.plexSans(size: 12))
                    .foregroundStyle(DesignTokens.textSecondary)
            }
            Spacer(minLength: 8)
            if !granted {
                Button("Request") {
                    switch kind {
                    case .accessibility: viewModel.requestAccessibility()
                    case .screenRecording: viewModel.requestScreenRecording()
                    }
                }
            }
            Button("Open Settings") {
                switch kind {
                case .accessibility: viewModel.openAccessibilitySettings()
                case .screenRecording: viewModel.openScreenRecordingSettings()
                }
            }
        }
        .padding(12)
        .background(
            DesignTokens.surfaceCard,
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
                .strokeBorder(DesignTokens.borderSubtle, lineWidth: 1)
        }
    }

    private func statusBadge(granted: Bool) -> some View {
        Text(granted ? "Granted" : "Not granted")
            .font(.plexMono(size: 11, weight: .medium))
            .foregroundStyle(granted ? DesignTokens.successGreenText : DesignTokens.warningAmber)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(
                (granted ? DesignTokens.successGreen : DesignTokens.warningAmber).opacity(0.12),
                in: RoundedRectangle(cornerRadius: DesignTokens.Radius.chip, style: .continuous)
            )
    }

    // MARK: - Connection (transport)

    private var connectionSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("Connection")
            Toggle(isOn: wifiBinding) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Allow Wi-Fi (LAN) connections")
                        .font(.plexSans(size: 14, weight: .medium))
                        .foregroundStyle(DesignTokens.textPrimary)
                    Text("Let tablets on this network connect directly. Applies on next Start.")
                        .font(.plexSans(size: 12))
                        .foregroundStyle(DesignTokens.textSecondary)
                }
            }
            .toggleStyle(.switch)
            .tint(DesignTokens.accentSolid)

            if viewModel.wifiEnabled {
                wifiDetails
            }
        }
    }

    /// The Mac's address(es) to type into the tablet, plus the plaintext/dev-only warning.
    private var wifiDetails: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                Text("This Mac's address")
                    .font(.plexSans(size: 12, weight: .medium))
                    .foregroundStyle(DesignTokens.textSecondary)
                if viewModel.localNetworkAddresses.isEmpty {
                    Text("No active network found")
                        .font(.plexMono(size: 12))
                        .foregroundStyle(DesignTokens.textTertiary)
                } else {
                    ForEach(viewModel.localNetworkAddresses, id: \.self) { address in
                        Text(address)
                            .font(.plexMono(size: 13))
                            .foregroundStyle(DesignTokens.textPrimary)
                            .textSelection(.enabled)
                    }
                }
                Text("Enter this in DeskLink on the tablet (Settings → Connection → Wi-Fi).")
                    .font(.plexSans(size: 11))
                    .foregroundStyle(DesignTokens.textTertiary)
            }

            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(DesignTokens.warningAmber)
                    .font(.system(size: 12))
                Text("Wi-Fi is experimental and unencrypted. Anyone on this network can "
                    + "view and control your Mac while connected. Use only on a trusted "
                    + "private network — USB stays the secure default.")
                    .font(.plexSans(size: 12))
                    .foregroundStyle(DesignTokens.textSecondary)
            }
        }
        .padding(12)
        .background(
            DesignTokens.warningAmber.opacity(0.08),
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
                .strokeBorder(DesignTokens.warningAmber.opacity(0.24), lineWidth: 1)
        }
    }

    private var wifiBinding: Binding<Bool> {
        Binding(get: { viewModel.wifiEnabled }, set: { viewModel.wifiEnabled = $0 })
    }

    // MARK: - Diagnostics

    private var diagnosticsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("Diagnostics")
            Toggle(isOn: verboseBinding) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Verbose diagnostic logging")
                        .font(.plexSans(size: 14, weight: .medium))
                        .foregroundStyle(DesignTokens.textPrimary)
                    Text("Includes per-frame capture/stream traces")
                        .font(.plexSans(size: 12))
                        .foregroundStyle(DesignTokens.textSecondary)
                }
            }
            .toggleStyle(.switch)
            .tint(DesignTokens.accentSolid)

            // The log viewer is opt-in: hidden until the user turns this on.
            Toggle(isOn: $showLogs) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Show log viewer")
                        .font(.plexSans(size: 14, weight: .medium))
                        .foregroundStyle(DesignTokens.textPrimary)
                    Text("Reveals the recent DeskLink log in this window")
                        .font(.plexSans(size: 12))
                        .foregroundStyle(DesignTokens.textSecondary)
                }
            }
            .toggleStyle(.switch)
            .tint(DesignTokens.accentSolid)

            if showLogs {
                HStack(spacing: 10) {
                    Button("Refresh") { viewModel.refreshLogs() }
                    Button("Copy") { viewModel.copyLogs() }
                    Button("Open Console") { viewModel.openConsole() }
                    if let status = viewModel.diagnosticsStatus {
                        Text(status)
                            .font(.plexMono(size: 11))
                            .foregroundStyle(DesignTokens.textTertiary)
                    }
                }
            }
        }
    }

    // MARK: - Log viewer

    /// Scrollable, selectable panel showing the recent DeskLink log. Expands to fill
    /// the remaining window height so a larger window shows more lines.
    private var logViewer: some View {
        ScrollView {
            Text(viewModel.logText.isEmpty ? "No logs loaded yet. Tap Refresh." : viewModel.logText)
                .font(.plexMono(size: 11))
                .foregroundStyle(DesignTokens.textSecondary)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
        }
        // Fixed, readable height so the content (and thus the window) has a definite
        // size; the panel scrolls when the log is longer.
        .frame(height: 420)
        .background(
            DesignTokens.surfaceCard,
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
                .strokeBorder(DesignTokens.borderSubtle, lineWidth: 1)
        }
    }

    private var verboseBinding: Binding<Bool> {
        Binding(get: { viewModel.verboseLogging }, set: { viewModel.verboseLogging = $0 })
    }

    // MARK: - Shared

    private func sectionTitle(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.plexMono(size: 11, weight: .medium))
            .foregroundStyle(DesignTokens.textQuaternary)
            .tracking(1.5)
    }
}
