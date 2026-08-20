import SwiftUI
import AppKit
import CoreText

enum DesignTokens {

    // MARK: - Colors (README "Design Tokens › Colors")

    static let textPrimary = Color(hex: 0xEAEDF3)
    static let textSecondary = Color(hex: 0x98A0AF)
    static let textTertiary = Color(hex: 0x7A8290)
    static let textQuaternary = Color(hex: 0x626A78)
    static let statusLabel = Color(hex: 0xB9C0CC)
    static let ghostText = Color(hex: 0xD3D8E2)
    static let quitText = Color(hex: 0x9AA3B2)

    static let accentSolid = Color(hex: 0x5B6BFF)
    static let accentLight = Color(hex: 0x7C86FF)
    static let accentViolet = Color(hex: 0x8A5BFF)
    static let accentBlue = Color(hex: 0x7079FF)

    static let successGreen = Color(hex: 0x35D0A5)
    static let successGreenText = Color(hex: 0x4FE0BA)
    static let warningAmber = Color(hex: 0xE0A64B)

    static let errorRedText = Color(hex: 0xFF8A8A)

    static let logStream = Color(hex: 0xAAB4FF)       // indigo — stream category tag
    static let logTimestamp = Color(hex: 0x5B6270)    // grey mono leading time
    static let logBody = Color(hex: 0x7F8794)         // message text
    static let consoleBg = Color(hex: 0x070809)       // console inner background

    static let surfaceCard = Color.white.opacity(0.04)      // stats card bg
    static let surfaceChip = Color.white.opacity(0.05)      // status meta chip bg
    static let surfaceHover = Color.white.opacity(0.06)     // ghost-row hover
    static let borderSubtle = Color.white.opacity(0.07)     // dividers, card border
    static let borderStrong = Color.white.opacity(0.10)     // popover border

    static let stopBg = Color(hex: 0xFF5C5C, alpha: 0.10)
    static let stopBgHover = Color(hex: 0xFF5C5C, alpha: 0.16)
    static let stopBorder = Color(hex: 0xFF5C5C, alpha: 0.28)
    static let quitHoverBg = Color(hex: 0xFF5C5C, alpha: 0.10)

    static let livePillBg = Color(hex: 0x35D0A5, alpha: 0.12)
    static let livePillBorder = Color(hex: 0x35D0A5, alpha: 0.25)

    static let pairingCardBorder = accentLight.opacity(0.24)
    static let pairingRingBorder = accentLight.opacity(0.28)   // "actionable" panel border
    static let pairingRingGlow = accentLight.opacity(0.22)     // soft indigo glow (ring approx.)
    static let pinCellBorder = accentLight.opacity(0.34)
    static let pairingLabel = Color(hex: 0x8F97B5)             // "PAIRING PIN" mono label
    static let pairingCopyText = Color(hex: 0xAAB4FF)          // Copy button text/icon
    static let copyButtonBg = Color.white.opacity(0.04)
    static let copyButtonBorder = Color.white.opacity(0.12)

    // MARK: - Gradients

    static let appGlyphGradient = LinearGradient(
        stops: [
            .init(color: accentLight, location: 0.0),
            .init(color: accentSolid, location: 0.55),
            .init(color: accentViolet, location: 1.0),
        ],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    static let primaryButtonGradient = LinearGradient(
        colors: [accentBlue, accentSolid],
        startPoint: .top,
        endPoint: .bottom
    )

    static let panelGradient = LinearGradient(
        colors: [
            Color(.sRGB, red: 32 / 255, green: 36 / 255, blue: 45 / 255, opacity: 0.94),
            Color(.sRGB, red: 18 / 255, green: 21 / 255, blue: 27 / 255, opacity: 0.96),
        ],
        startPoint: .top,
        endPoint: .bottom
    )

    static let pairingCardGradient = LinearGradient(
        colors: [accentLight.opacity(0.10), accentLight.opacity(0.03)],
        startPoint: .top,
        endPoint: .bottom
    )

    static let pinCellGradient = LinearGradient(
        colors: [accentLight.opacity(0.20), accentLight.opacity(0.08)],
        startPoint: .top,
        endPoint: .bottom
    )

    // MARK: - Radii

    enum Radius {
        static let panel: CGFloat = 16
        static let glyph: CGFloat = 9
        static let primaryButton: CGFloat = 11
        static let ghostRow: CGFloat = 10
        static let statsCard: CGFloat = 12
        static let card: CGFloat = 13       // status banner, pairing block
        static let pairingCard: CGFloat = 14 // popover pairing-PIN card
        static let pinCell: CGFloat = 10    // popover PIN digit cell
        static let small: CGFloat = 8       // PIN digit chip, console toolbar button
        static let chip: CGFloat = 6
    }

    // MARK: - Shadows (approximations of the CSS box-shadows; SwiftUI has no spread)

    enum PanelShadow {
        static let color = Color.black.opacity(0.55)
        static let radius: CGFloat = 26
        static let y: CGFloat = 22
    }

    enum PrimaryButtonShadow {
        static let color = Color(.sRGB, red: 91 / 255, green: 107 / 255, blue: 255 / 255, opacity: 0.85)
        static let radius: CGFloat = 8
        static let y: CGFloat = 7
    }
}

// MARK: - Color hex helper

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        let r = Double((hex >> 16) & 0xFF) / 255
        let g = Double((hex >> 8) & 0xFF) / 255
        let b = Double(hex & 0xFF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}

// MARK: - Font registration + helpers

enum PlexFonts {

    // Exact PostScript names from the ttf name tables: Mono abbreviations ("Medm", "SmBld") are correct, not typos.
    enum Sans {
        static let regular = "IBMPlexSans-Regular"
        static let medium = "IBMPlexSans-Medium"
        static let semibold = "IBMPlexSans-SemiBold"
        static let bold = "IBMPlexSans-Bold"
    }
    enum Mono {
        static let regular = "IBMPlexMono"
        static let medium = "IBMPlexMono-Medm"
        static let semibold = "IBMPlexMono-SmBld"
    }

    static let sansAvailable: Bool = {
        registerBundledFaces()
        return NSFont(name: Sans.regular, size: 12) != nil
    }()

    static let monoAvailable: Bool = {
        _ = sansAvailable // guarantees registerBundledFaces() has already run
        return NSFont(name: Mono.regular, size: 12) != nil
    }()

    static func register() {
        _ = sansAvailable
        _ = monoAvailable
    }

    // Bundle layout varies: .process may flatten "Resources" or keep "Fonts/", so both are searched; re-registering a URL fails harmlessly.
    private static func registerBundledFaces() {
        var urls: [URL] = []
        urls += Bundle.module.urls(forResourcesWithExtension: "ttf", subdirectory: nil) ?? []
        urls += Bundle.module.urls(forResourcesWithExtension: "ttf", subdirectory: "Fonts") ?? []
        for url in urls {
            var error: Unmanaged<CFError>?
            _ = CTFontManagerRegisterFontsForURL(url as CFURL, .process, &error)
            error?.release()
        }
    }

    static func sansName(for weight: Font.Weight) -> String {
        switch weight {
        case .medium: return Sans.medium
        case .semibold: return Sans.semibold
        case .bold, .heavy, .black: return Sans.bold
        default: return Sans.regular
        }
    }

    static func monoName(for weight: Font.Weight) -> String {
        switch weight {
        case .semibold, .bold, .heavy, .black: return Mono.semibold
        case .medium: return Mono.medium
        default: return Mono.regular
        }
    }
}

extension Font {
    static func plexSans(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        guard PlexFonts.sansAvailable else {
            return .system(size: size, weight: weight)
        }
        return .custom(PlexFonts.sansName(for: weight), fixedSize: size)
    }

    static func plexMono(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        guard PlexFonts.monoAvailable else {
            return .system(size: size, weight: weight, design: .monospaced)
        }
        return .custom(PlexFonts.monoName(for: weight), fixedSize: size)
    }
}
