import SwiftUI

enum SettingsWindowID {
    static let value = "settings"
}

@MainActor
struct SettingsView: View {
    let viewModel: SettingsViewModel
    let serverViewModel: ServerViewModel

    @State private var showLogs = false

    private enum PermissionKind { case accessibility, screenRecording }

    private let windowWidth: CGFloat = 560

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Text("DeskLink Settings")
                .font(.plexSans(size: 18, weight: .semibold))
                .foregroundStyle(DesignTokens.textPrimary)

            statusBanner
            permissionsSection
            connectionSection
            diagnosticsSection
        }
        .padding(22)
        .frame(width: windowWidth, alignment: .topLeading)
        .background(DesignTokens.panelGradient, ignoresSafeAreaEdges: .all)
        .task {
            while !Task.isCancelled {
                viewModel.refresh()
                viewModel.tickPairing(connected: serverViewModel.status == .connected)
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
        .onChange(of: showLogs) { _, isShown in
            if isShown { viewModel.refreshLogs() }
        }
    }

    // MARK: - Server status banner

    private var statusBanner: some View {
        let running = serverViewModel.status != .disconnected
        let dotColor = running ? DesignTokens.successGreen : DesignTokens.textTertiary
        let title = running ? "Server running" : "Server stopped"
        let subtitle = running
            ? (serverViewModel.wifiListening ? "Listening on USB + Wi-Fi" : "Listening on USB")
            : "Not listening"

        return HStack(spacing: 12) {
            Circle()
                .fill(dotColor)
                .frame(width: 10, height: 10)
                .shadow(color: dotColor.opacity(running ? 0.8 : 0), radius: 5)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.plexSans(size: 14.5, weight: .semibold))
                    .foregroundStyle(DesignTokens.textPrimary)
                Text(subtitle)
                    .font(.plexSans(size: 12.5))
                    .foregroundStyle(DesignTokens.textSecondary)
            }
            Spacer(minLength: 8)
            if running {
                bannerButton(
                    "Stop",
                    textColor: DesignTokens.errorRedText,
                    background: DesignTokens.stopBg,
                    border: DesignTokens.stopBorder
                ) { serverViewModel.stop() }
            } else {
                bannerButton(
                    "Start",
                    textColor: DesignTokens.accentLight,
                    background: DesignTokens.accentSolid.opacity(0.14),
                    border: DesignTokens.accentSolid.opacity(0.4)
                ) { serverViewModel.start() }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(
            (running ? DesignTokens.successGreen.opacity(0.08) : DesignTokens.surfaceCard),
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.card, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: DesignTokens.Radius.card, style: .continuous)
                .strokeBorder(
                    running ? DesignTokens.successGreen.opacity(0.22) : DesignTokens.borderSubtle,
                    lineWidth: 1
                )
        }
    }

    private func bannerButton(
        _ title: String, textColor: Color, background: Color, border: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(title)
                .font(.plexSans(size: 13, weight: .semibold))
                .foregroundStyle(textColor)
                .padding(.horizontal, 15)
                .frame(height: 34)
                .background(background, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.ghostRow, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: DesignTokens.Radius.ghostRow, style: .continuous)
                        .strokeBorder(border, lineWidth: 1)
                }
        }
        .buttonStyle(.plain)
    }

    // MARK: - Permissions

    private var permissionsSection: some View {
        VStack(alignment: .leading, spacing: 9) {
            sectionTitle("Permissions")
            permissionCard(.accessibility)
            permissionCard(.screenRecording)
        }
    }

    private func permissionCard(_ kind: PermissionKind) -> some View {
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
        let tint = granted ? DesignTokens.successGreen : DesignTokens.warningAmber
        let iconColor = granted ? DesignTokens.successGreenText : DesignTokens.warningAmber

        return HStack(alignment: .center, spacing: 13) {
            ZStack {
                RoundedRectangle(cornerRadius: DesignTokens.Radius.glyph, style: .continuous)
                    .fill(tint.opacity(0.12))
                Image(systemName: granted ? "checkmark" : "exclamationmark")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(iconColor)
            }
            .frame(width: 34, height: 34)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(title)
                        .font(.plexSans(size: 14, weight: .semibold))
                        .foregroundStyle(DesignTokens.textPrimary)
                    grantedPill(granted)
                }
                Text(subtitle)
                    .font(.plexSans(size: 12.5))
                    .foregroundStyle(DesignTokens.textSecondary)
            }
            Spacer(minLength: 8)
            if !granted {
                HStack(spacing: 6) {
                    Button("Request") {
                        switch kind {
                        case .accessibility: viewModel.requestAccessibility()
                        case .screenRecording: viewModel.requestScreenRecording()
                        }
                    }
                    Button("Open") {
                        switch kind {
                        case .accessibility: viewModel.openAccessibilitySettings()
                        case .screenRecording: viewModel.openScreenRecordingSettings()
                        }
                    }
                }
                .controlSize(.small)
            }
        }
        .padding(.horizontal, 15)
        .padding(.vertical, 13)
        .background(
            DesignTokens.surfaceCard,
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
                .strokeBorder(DesignTokens.borderSubtle, lineWidth: 1)
        }
    }

    private func grantedPill(_ granted: Bool) -> some View {
        let color = granted ? DesignTokens.successGreenText : DesignTokens.warningAmber
        let tint = granted ? DesignTokens.successGreen : DesignTokens.warningAmber
        return Text(granted ? "GRANTED" : "NOT GRANTED")
            .font(.plexMono(size: 10, weight: .medium))
            .foregroundStyle(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(tint.opacity(0.12), in: Capsule())
            .overlay { Capsule().strokeBorder(tint.opacity(0.25), lineWidth: 1) }
    }

    // MARK: - Connection (transport + pairing)

    private var connectionSection: some View {
        VStack(alignment: .leading, spacing: 9) {
            sectionTitle("Connection")
            toggleCard(
                "Allow Wi-Fi (LAN) connections",
                "Let tablets on this network connect directly. Applies on next Start.",
                isOn: wifiBinding
            )
            if viewModel.wifiEnabled {
                pairingBlock(connected: serverViewModel.status == .connected)
            }
        }
    }

    private func pairingBlock(connected: Bool) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 22) {
                addressColumn.frame(maxWidth: .infinity, alignment: .leading)
                pinColumn(connected: connected)
            }
            Rectangle()
                .fill(DesignTokens.accentLight.opacity(0.16))
                .frame(height: 1)
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(DesignTokens.warningAmber)
                    .font(.system(size: 12))
                Text("Wi-Fi is experimental. Traffic is encrypted (TLS); pair with the PIN "
                    + "above and use only on a trusted network. USB stays the default.")
                    .font(.plexSans(size: 12))
                    .foregroundStyle(DesignTokens.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(16)
        .background(
            DesignTokens.accentLight.opacity(0.06),
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.card, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: DesignTokens.Radius.card, style: .continuous)
                .strokeBorder(DesignTokens.accentLight.opacity(0.22), lineWidth: 1)
        }
    }

    private var addressColumn: some View {
        VStack(alignment: .leading, spacing: 7) {
            monoLabel("This Mac's address")
            if viewModel.localNetworkAddresses.isEmpty {
                Text("No active network found")
                    .font(.plexMono(size: 13))
                    .foregroundStyle(DesignTokens.textTertiary)
            } else {
                VStack(alignment: .leading, spacing: 5) {
                    ForEach(Array(viewModel.localNetworkAddresses.enumerated()), id: \.offset) { index, address in
                        if index == 0 {
                            HStack(spacing: 8) {
                                Text(address)
                                    .font(.plexMono(size: 14))
                                    .foregroundStyle(DesignTokens.textPrimary)
                                    .textSelection(.enabled)
                                Button { viewModel.copyAddress(address) } label: {
                                    Image(systemName: "doc.on.doc")
                                        .font(.system(size: 12))
                                        .foregroundStyle(DesignTokens.textTertiary)
                                }
                                .buttonStyle(.plain)
                                .help("Copy address")
                            }
                        } else {
                            Text(address)
                                .font(.plexMono(size: 14))
                                .foregroundStyle(DesignTokens.textSecondary)
                                .textSelection(.enabled)
                        }
                    }
                }
            }
        }
    }

    private func pinColumn(connected: Bool) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            monoLabel("Pairing PIN")
            if connected {
                Text("Not needed while connected")
                    .font(.plexSans(size: 12))
                    .foregroundStyle(DesignTokens.textTertiary)
                    .frame(height: 38)
            } else {
                HStack(spacing: 5) {
                    ForEach(Array(viewModel.pairingPin.enumerated()), id: \.offset) { _, digit in
                        Text(String(digit))
                            .font(.plexMono(size: 19, weight: .semibold))
                            .foregroundStyle(Color.white)
                            .frame(width: 30, height: 38)
                            .background(
                                DesignTokens.accentLight.opacity(0.16),
                                in: RoundedRectangle(cornerRadius: DesignTokens.Radius.small, style: .continuous)
                            )
                            .overlay {
                                RoundedRectangle(cornerRadius: DesignTokens.Radius.small, style: .continuous)
                                    .strokeBorder(DesignTokens.accentLight.opacity(0.3), lineWidth: 1)
                            }
                    }
                }
                Text(viewModel.pairingSecondsRemaining > 0
                    ? "New code in \(viewModel.pairingSecondsRemaining)s"
                    : "Refreshing…")
                    .font(.plexMono(size: 11))
                    .foregroundStyle(DesignTokens.textTertiary)
            }
        }
    }

    private var wifiBinding: Binding<Bool> {
        Binding(get: { viewModel.wifiEnabled }, set: { viewModel.wifiEnabled = $0 })
    }

    // MARK: - Diagnostics

    private var diagnosticsSection: some View {
        VStack(alignment: .leading, spacing: 9) {
            sectionTitle("Diagnostics")
            toggleCard(
                "Verbose diagnostic logging",
                "Includes per-frame capture / stream traces",
                isOn: verboseBinding
            )
            toggleCard(
                "Show log viewer",
                "Reveals the recent DeskLink log in this window",
                isOn: $showLogs
            )
            if showLogs {
                console
            }
        }
    }

    private var console: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                consoleButton("Refresh", systemImage: "arrow.clockwise") { viewModel.refreshLogs() }
                consoleButton("Copy", systemImage: "doc.on.doc") { viewModel.copyLogs() }
                consoleButton("Open Console", systemImage: "arrow.up.forward.app") { viewModel.openConsole() }
                Spacer(minLength: 8)
                Text(viewModel.diagnosticsStatus ?? "Last 5 min")
                    .font(.plexMono(size: 11))
                    .foregroundStyle(DesignTokens.textQuaternary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(DesignTokens.surfaceCard)
            .overlay(alignment: .bottom) {
                Rectangle().fill(DesignTokens.borderSubtle).frame(height: 1)
            }

            ScrollView {
                consoleBody
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .textSelection(.enabled)
            }
            .frame(maxHeight: 236)
        }
        .background(
            DesignTokens.consoleBg,
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
                .strokeBorder(DesignTokens.borderStrong, lineWidth: 1)
        }
    }

    private var consoleBody: some View {
        let lines = viewModel.logLines
        return VStack(alignment: .leading, spacing: 0) {
            if lines.isEmpty {
                Text("No logs loaded yet. Tap Refresh.")
                    .font(.plexMono(size: 11))
                    .foregroundStyle(DesignTokens.logBody)
            } else {
                ForEach(lines) { line in
                    consoleRow(line)
                        .font(.plexMono(size: 11))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .lineSpacing(6)
    }

    private func consoleRow(_ line: DiagnosticLogLine) -> Text {
        var text = Text("")
        if let timestamp = line.timestamp {
            text = text + Text(timestamp).foregroundStyle(DesignTokens.logTimestamp) + Text(" ")
        }
        if let category = line.category {
            text = text + Text(category).foregroundStyle(categoryColor(category)) + Text(" ")
        }
        return text + Text(line.message).foregroundStyle(DesignTokens.logBody)
    }

    private func categoryColor(_ category: String) -> Color {
        switch category {
        case Log.Category.server.rawValue: return DesignTokens.successGreenText
        case Log.Category.stream.rawValue: return DesignTokens.logStream
        case Log.Category.capture.rawValue: return DesignTokens.warningAmber
        case Log.Category.adb.rawValue: return DesignTokens.accentLight
        default: return DesignTokens.logBody
        }
    }

    private func consoleButton(
        _ title: String, systemImage: String, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: systemImage).font(.system(size: 12))
                Text(title).font(.plexSans(size: 12.5, weight: .medium))
            }
            .foregroundStyle(DesignTokens.ghostText)
            .padding(.horizontal, 12)
            .frame(height: 30)
            .background(
                DesignTokens.surfaceChip,
                in: RoundedRectangle(cornerRadius: DesignTokens.Radius.small, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: DesignTokens.Radius.small, style: .continuous)
                    .strokeBorder(DesignTokens.borderStrong, lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
    }

    private var verboseBinding: Binding<Bool> {
        Binding(get: { viewModel.verboseLogging }, set: { viewModel.verboseLogging = $0 })
    }

    // MARK: - Shared

    private func toggleCard(_ title: String, _ subtitle: String, isOn: Binding<Bool>) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.plexSans(size: 14, weight: .semibold))
                    .foregroundStyle(DesignTokens.textPrimary)
                Text(subtitle)
                    .font(.plexSans(size: 12.5))
                    .foregroundStyle(DesignTokens.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Toggle("", isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .tint(DesignTokens.accentSolid)
        }
        .padding(.horizontal, 15)
        .padding(.vertical, 13)
        .background(
            DesignTokens.surfaceCard,
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
                .strokeBorder(DesignTokens.borderSubtle, lineWidth: 1)
        }
    }

    private func monoLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.plexMono(size: 10.5))
            .tracking(1)
            .foregroundStyle(DesignTokens.textTertiary)
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.plexMono(size: 11, weight: .medium))
            .foregroundStyle(DesignTokens.textQuaternary)
            .tracking(1.5)
    }
}
