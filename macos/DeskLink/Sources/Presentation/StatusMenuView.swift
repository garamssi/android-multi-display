import SwiftUI
import AppKit

@MainActor
struct StatusMenuView: View {
    let viewModel: ServerViewModel

    @Environment(\.openWindow) private var openWindow

    private var showPin: Bool {
        viewModel.status == .connecting && viewModel.wifiListening
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            headerRow

            divider

            statusRow
                .padding(.bottom, viewModel.status == .connected ? 12 : 14)

            if viewModel.status == .connected {
                statsCard
                    .padding(.bottom, 12)
            } else if showPin {
                pinBlock
                    .padding(.bottom, 12)
            } else if viewModel.status == .connecting {
                usbOnlyNote
                    .padding(.bottom, 12)
            }

            primaryButton

            ghostRows
        }
        .padding(15)
        .frame(width: 320, alignment: .leading)
        .background(panelBackground)
        .clipShape(RoundedRectangle(cornerRadius: DesignTokens.Radius.panel, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: DesignTokens.Radius.panel, style: .continuous)
                .strokeBorder(
                    showPin ? DesignTokens.pairingRingBorder : DesignTokens.borderStrong,
                    lineWidth: 1
                )
        )
        .shadow(
            color: DesignTokens.PanelShadow.color,
            radius: DesignTokens.PanelShadow.radius,
            x: 0,
            y: DesignTokens.PanelShadow.y
        )
        .shadow(color: showPin ? DesignTokens.pairingRingGlow : .clear, radius: showPin ? 10 : 0)
        .padding(8) // give the shadow / rounded corners room inside the menu window
        .task {
            while !Task.isCancelled {
                viewModel.tickPairing()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: viewModel.status)
    }

    // MARK: - Panel background (translucent gradient over a blur)

    private var panelBackground: some View {
        ZStack {
            VisualEffectBackground()
            DesignTokens.panelGradient
        }
    }

    private var divider: some View {
        Rectangle()
            .fill(DesignTokens.borderSubtle)
            .frame(height: 1)
    }

    // MARK: - Header

    private var appVersionLabel: String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String)
            .map { "v\($0)" } ?? "—"
    }

    private var headerRow: some View {
        HStack(spacing: 11) {
            appGlyph

            VStack(alignment: .leading, spacing: 2) {
                Text("DeskLink Server")
                    .font(.plexSans(size: 15, weight: .semibold))
                    .foregroundStyle(DesignTokens.textPrimary)
                Text(appVersionLabel)
                    .font(.plexMono(size: 11))
                    .foregroundStyle(DesignTokens.textTertiary)
            }

            if viewModel.status == .connected {
                Spacer(minLength: 8)
                livePill
            }
        }
        .padding(.horizontal, 4)
        .padding(.top, 2)
        .padding(.bottom, 14)
    }

    private var appGlyph: some View {
        RoundedRectangle(cornerRadius: DesignTokens.Radius.glyph, style: .continuous)
            .fill(DesignTokens.appGlyphGradient)
            .frame(width: 32, height: 32)
            .overlay(
                RoundedRectangle(cornerRadius: DesignTokens.Radius.glyph, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.4), lineWidth: 1)
                    .mask(LinearGradient(colors: [.white, .clear], startPoint: .top, endPoint: .center))
            )
            .overlay(
                Image(systemName: "display")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
            )
    }

    private var livePill: some View {
        Text("LIVE")
            .font(.plexMono(size: 10.5, weight: .medium))
            .foregroundStyle(DesignTokens.successGreenText)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(Capsule().fill(DesignTokens.livePillBg))
            .overlay(Capsule().strokeBorder(DesignTokens.livePillBorder, lineWidth: 1))
    }

    // MARK: - Status row

    private var statusRow: some View {
        HStack(spacing: 10) {
            statusDot

            Text(statusLabelText)
                .font(.plexSans(size: 14, weight: statusLabelWeight))
                .foregroundStyle(statusLabelColor)

            Spacer(minLength: 8)

            statusMeta
        }
        .padding(.horizontal, 4)
        .padding(.top, 14)
    }

    @ViewBuilder private var statusDot: some View {
        switch viewModel.status {
        case .connected:
            GlowDot(color: DesignTokens.successGreen)
        default:
            PulsingDot(color: DesignTokens.warningAmber)
        }
    }

    private var statusLabelText: String {
        switch viewModel.status {
        case .disconnected: return "Server stopped"
        case .connecting: return "Waiting for device"
        case .connected: return "Connected"
        }
    }

    private var statusLabelColor: Color {
        viewModel.status == .connected ? DesignTokens.textPrimary : DesignTokens.statusLabel
    }

    private var statusLabelWeight: Font.Weight {
        viewModel.status == .connected ? .medium : .regular
    }

    @ViewBuilder private var statusMeta: some View {
        switch viewModel.status {
        case .connected:
            Text(viewModel.uptime)
                .font(.plexMono(size: 11))
                .foregroundStyle(DesignTokens.textTertiary)
                .monospacedDigit()
        case .connecting:
            metaChip(viewModel.wifiListening ? "USB · Wi-Fi" : "USB")
        case .disconnected:
            metaChip("offline")
        }
    }

    private func metaChip(_ text: String) -> some View {
        Text(text)
            .font(.plexMono(size: 11, weight: .medium))
            .foregroundStyle(DesignTokens.textTertiary)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(
                RoundedRectangle(cornerRadius: DesignTokens.Radius.chip, style: .continuous)
                    .fill(DesignTokens.surfaceChip)
            )
    }

    // MARK: - Stats card (connected only)

    private var statsCard: some View {
        VStack(spacing: 13) {
            HStack(spacing: 16) {
                StatField(label: "Device", value: viewModel.deviceName ?? "—")
                StatField(label: "Link", value: viewModel.link)
            }
            HStack(spacing: 16) {
                StatField(label: "Output", value: viewModel.output ?? "—")
                StatField(label: "Frame", value: viewModel.frame ?? "—")
            }
        }
        .padding(.vertical, 13)
        .padding(.horizontal, 14)
        .background(
            RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
                .fill(DesignTokens.surfaceCard)
        )
        .overlay(
            RoundedRectangle(cornerRadius: DesignTokens.Radius.statsCard, style: .continuous)
                .strokeBorder(DesignTokens.borderSubtle, lineWidth: 1)
        )
    }

    // MARK: - Pairing PIN (waiting state)

    private var pinBlock: some View {
        VStack(alignment: .leading, spacing: 11) {
            HStack(spacing: 8) {
                Text("PAIRING PIN")
                    .font(.plexMono(size: 10.5, weight: .medium))
                    .tracking(1.4)
                    .foregroundStyle(DesignTokens.pairingLabel)
                Spacer(minLength: 8)
                copyButton
            }
            HStack(spacing: 7) {
                ForEach(Array(viewModel.pairingPin.enumerated()), id: \.offset) { _, digit in
                    Text(String(digit))
                        .font(.plexMono(size: 22, weight: .semibold))
                        .foregroundStyle(DesignTokens.textPrimary)
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(
                            DesignTokens.pinCellGradient,
                            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.pinCell, style: .continuous)
                        )
                        .overlay {
                            RoundedRectangle(cornerRadius: DesignTokens.Radius.pinCell, style: .continuous)
                                .strokeBorder(DesignTokens.pinCellBorder, lineWidth: 1)
                        }
                }
            }
            HStack(spacing: 6) {
                Image(systemName: "clock")
                    .font(.system(size: 11))
                    .foregroundStyle(DesignTokens.textTertiary)
                Text("New code in \(viewModel.pairingSecondsRemaining)s · enter on tablet")
                    .font(.plexMono(size: 11))
                    .foregroundStyle(DesignTokens.textTertiary)
            }
        }
        .padding(14)
        .background(
            DesignTokens.pairingCardGradient,
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.pairingCard, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: DesignTokens.Radius.pairingCard, style: .continuous)
                .strokeBorder(DesignTokens.pairingCardBorder, lineWidth: 1)
        }
    }

    private var copyButton: some View {
        Button { viewModel.copyPairingPin() } label: {
            HStack(spacing: 5) {
                Image(systemName: "doc.on.doc").font(.system(size: 11))
                Text("Copy").font(.plexSans(size: 11, weight: .medium))
            }
            .foregroundStyle(DesignTokens.pairingCopyText)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(
                DesignTokens.copyButtonBg,
                in: RoundedRectangle(cornerRadius: DesignTokens.Radius.chip, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: DesignTokens.Radius.chip, style: .continuous)
                    .strokeBorder(DesignTokens.copyButtonBorder, lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
    }

    private var usbOnlyNote: some View {
        HStack(spacing: 8) {
            Image(systemName: "cable.connector")
                .font(.system(size: 12))
                .foregroundStyle(DesignTokens.textTertiary)
            Text("USB only — turn on Wi-Fi in Settings to pair over LAN.")
                .font(.plexSans(size: 12))
                .foregroundStyle(DesignTokens.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 4)
    }

    // MARK: - Primary action

    @ViewBuilder private var primaryButton: some View {
        if viewModel.status == .disconnected {
            Button { viewModel.start() } label: {
                HStack(spacing: 8) {
                    Image(systemName: "play.fill").font(.system(size: 11, weight: .semibold))
                    Text("Start Server").font(.plexSans(size: 14, weight: .semibold))
                }
            }
            .buttonStyle(AccentButtonStyle())
        } else {
            Button { viewModel.stop() } label: {
                HStack(spacing: 8) {
                    Image(systemName: "stop.fill").font(.system(size: 11, weight: .semibold))
                    Text("Stop Server").font(.plexSans(size: 14, weight: .semibold))
                }
            }
            .buttonStyle(StopButtonStyle())
        }
    }

    // MARK: - Ghost rows

    private var ghostRows: some View {
        VStack(spacing: 0) {
            Button {
                openWindow(id: SettingsWindowID.value)
                NSApp.activate()
            } label: {
                HStack(spacing: 11) {
                    Image(systemName: "gearshape").font(.system(size: 15))
                    Text("Settings…").font(.plexSans(size: 14, weight: .medium))
                }
            }
            .buttonStyle(GhostRowButtonStyle(baseTextColor: DesignTokens.ghostText))
            .padding(.top, 6)

            Rectangle()
                .fill(DesignTokens.borderSubtle)
                .frame(height: 1)
                .padding(.vertical, 6)
                .padding(.horizontal, 4)

            Button {
                NSApplication.shared.terminate(nil)
            } label: {
                HStack(spacing: 11) {
                    Image(systemName: "rectangle.portrait.and.arrow.right").font(.system(size: 15))
                    Text("Quit DeskLink").font(.plexSans(size: 14, weight: .medium))
                }
            }
            .buttonStyle(
                GhostRowButtonStyle(
                    baseTextColor: DesignTokens.quitText,
                    hoverBg: DesignTokens.quitHoverBg,
                    hoverTextColor: DesignTokens.errorRedText
                )
            )
            .keyboardShortcut("q")
        }
    }
}

// MARK: - Stat field

private struct StatField: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased())
                .font(.plexMono(size: 10))
                .tracking(0.8) // letter-spacing .08em on 10px ≈ 0.8pt
                .foregroundStyle(DesignTokens.textQuaternary)
            Text(value)
                .font(.plexMono(size: 13))
                .foregroundStyle(DesignTokens.ghostText)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Status dots

private struct PulsingDot: View {
    let color: Color

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 9, height: 9)
            .shadow(color: color.opacity(0.7), radius: 5)
            .phaseAnimator([false, true]) { view, expanded in
                view
                    .scaleEffect(expanded ? 1.3 : 1.0)
                    .opacity(expanded ? 1.0 : 0.45)
            } animation: { _ in
                .easeInOut(duration: 0.9)
            }
    }
}

private struct GlowDot: View {
    let color: Color

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 9, height: 9)
            .shadow(color: color.opacity(0.8), radius: 6)
    }
}

// MARK: - Button styles (hover-aware)

private struct AccentButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Content(configuration: configuration)
    }

    // Named `Content`, not `Body`: a nested `Body` collides with ButtonStyle's `Body` associated type and breaks protocol inference.
    private struct Content: View {
        let configuration: ButtonStyleConfiguration
        @State private var hovering = false

        var body: some View {
            configuration.label
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 40)
                .background {
                    ZStack {
                        RoundedRectangle(cornerRadius: DesignTokens.Radius.primaryButton, style: .continuous)
                            .fill(DesignTokens.primaryButtonGradient)
                        RoundedRectangle(cornerRadius: DesignTokens.Radius.primaryButton, style: .continuous)
                            .strokeBorder(Color.white.opacity(0.3), lineWidth: 1)
                            .mask(LinearGradient(colors: [.white, .clear], startPoint: .top, endPoint: .center))
                    }
                }
                .brightness(hovering ? 0.06 : 0)
                .shadow(
                    color: DesignTokens.PrimaryButtonShadow.color,
                    radius: DesignTokens.PrimaryButtonShadow.radius,
                    x: 0,
                    y: DesignTokens.PrimaryButtonShadow.y
                )
                .opacity(configuration.isPressed ? 0.9 : 1)
                .contentShape(Rectangle())
                .onHover { hovering = $0 }
        }
    }
}

private struct StopButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Content(configuration: configuration)
    }

    private struct Content: View {
        let configuration: ButtonStyleConfiguration
        @State private var hovering = false

        var body: some View {
            configuration.label
                .foregroundStyle(DesignTokens.errorRedText)
                .frame(maxWidth: .infinity)
                .frame(height: 40)
                .background(
                    RoundedRectangle(cornerRadius: DesignTokens.Radius.primaryButton, style: .continuous)
                        .fill(hovering ? DesignTokens.stopBgHover : DesignTokens.stopBg)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: DesignTokens.Radius.primaryButton, style: .continuous)
                        .strokeBorder(DesignTokens.stopBorder, lineWidth: 1)
                )
                .opacity(configuration.isPressed ? 0.85 : 1)
                .contentShape(Rectangle())
                .onHover { hovering = $0 }
        }
    }
}

private struct GhostRowButtonStyle: ButtonStyle {
    let baseTextColor: Color
    var hoverBg: Color = DesignTokens.surfaceHover
    var hoverTextColor: Color? = nil

    func makeBody(configuration: Configuration) -> some View {
        Content(
            configuration: configuration,
            baseTextColor: baseTextColor,
            hoverBg: hoverBg,
            hoverTextColor: hoverTextColor
        )
    }

    private struct Content: View {
        let configuration: ButtonStyleConfiguration
        let baseTextColor: Color
        let hoverBg: Color
        let hoverTextColor: Color?
        @State private var hovering = false

        var body: some View {
            configuration.label
                .foregroundStyle(hovering ? (hoverTextColor ?? baseTextColor) : baseTextColor)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 11)
                .frame(height: 38)
                .background(
                    RoundedRectangle(cornerRadius: DesignTokens.Radius.ghostRow, style: .continuous)
                        .fill(hovering ? hoverBg : Color.clear)
                )
                .contentShape(Rectangle())
                .opacity(configuration.isPressed ? 0.7 : 1)
                .onHover { hovering = $0 }
        }
    }
}

// MARK: - Translucent blur backing

private struct VisualEffectBackground: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = .popover
        view.blendingMode = .behindWindow
        view.state = .active
        return view
    }

    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {}
}
