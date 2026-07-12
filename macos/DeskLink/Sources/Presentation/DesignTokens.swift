import SwiftUI
import AppKit
import CoreText

/// Design tokens for the DeskLink menu-bar popover, transcribed from the hi-fi
/// handoff (`design_handoff_desklink_os_ui`). Colors, gradients, shadows and radii
/// mirror the spec's CSS values; the `Font.plexSans` / `Font.plexMono` helpers
/// return the bundled IBM Plex faces and fall back to the system font when the
/// bundled ttf can't be registered/resolved.
enum DesignTokens {

    // MARK: - Colors (README "Design Tokens › Colors")

    /// Primary titles / values — `#EAEDF3`.
    static let textPrimary = Color(hex: 0xEAEDF3)
    /// Body / subtitles — `#98A0AF`.
    static let textSecondary = Color(hex: 0x98A0AF)
    /// Captions / mono meta — `#7A8290`.
    static let textTertiary = Color(hex: 0x7A8290)
    /// Mono stat labels — `#626A78`.
    static let textQuaternary = Color(hex: 0x626A78)
    /// "Not connected" status label — `#B9C0CC`.
    static let statusLabel = Color(hex: 0xB9C0CC)
    /// Ghost-row text & stat values — `#D3D8E2`.
    static let ghostText = Color(hex: 0xD3D8E2)
    /// Quit-row idle text — `#9AA3B2`.
    static let quitText = Color(hex: 0x9AA3B2)

    static let accentSolid = Color(hex: 0x5B6BFF)
    static let accentLight = Color(hex: 0x7C86FF)
    static let accentViolet = Color(hex: 0x8A5BFF)
    static let accentBlue = Color(hex: 0x7079FF)

    static let successGreen = Color(hex: 0x35D0A5)
    static let successGreenText = Color(hex: 0x4FE0BA)
    static let warningAmber = Color(hex: 0xE0A64B)

    static let errorRedText = Color(hex: 0xFF8A8A)

    // Diagnostics console: category tag colors + the panel's dim body/timestamp/bg.
    // `server` reuses `successGreenText`, `capture` reuses `warningAmber`.
    static let logStream = Color(hex: 0xAAB4FF)       // indigo — stream category tag
    static let logTimestamp = Color(hex: 0x5B6270)    // grey mono leading time
    static let logBody = Color(hex: 0x7F8794)         // message text
    static let consoleBg = Color(hex: 0x070809)       // console inner background

    // Surfaces & borders (white-tinted overlays).
    static let surfaceCard = Color.white.opacity(0.04)      // stats card bg
    static let surfaceChip = Color.white.opacity(0.05)      // "USB · idle" chip
    static let surfaceHover = Color.white.opacity(0.06)     // ghost-row hover
    static let borderSubtle = Color.white.opacity(0.07)     // dividers, card border
    static let borderStrong = Color.white.opacity(0.10)     // popover border

    // Red-tint (Stop / Quit-hover).
    static let stopBg = Color(hex: 0xFF5C5C, alpha: 0.10)
    static let stopBgHover = Color(hex: 0xFF5C5C, alpha: 0.16)
    static let stopBorder = Color(hex: 0xFF5C5C, alpha: 0.28)
    static let quitHoverBg = Color(hex: 0xFF5C5C, alpha: 0.10)

    // Pill / dot tints.
    static let livePillBg = Color(hex: 0x35D0A5, alpha: 0.12)
    static let livePillBorder = Color(hex: 0x35D0A5, alpha: 0.25)

    // MARK: - Gradients

    /// App-glyph tile — `linear-gradient(150deg, #7C86FF, #5B6BFF 55%, #8A5BFF)`.
    static let appGlyphGradient = LinearGradient(
        stops: [
            .init(color: accentLight, location: 0.0),
            .init(color: accentSolid, location: 0.55),
            .init(color: accentViolet, location: 1.0),
        ],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    /// Primary button — `linear-gradient(180deg, #7079FF, #5B6BFF)`.
    static let primaryButtonGradient = LinearGradient(
        colors: [accentBlue, accentSolid],
        startPoint: .top,
        endPoint: .bottom
    )

    /// Popover panel — `linear-gradient(180deg, rgba(32,36,45,.94), rgba(18,21,27,.96))`.
    static let panelGradient = LinearGradient(
        colors: [
            Color(.sRGB, red: 32 / 255, green: 36 / 255, blue: 45 / 255, opacity: 0.94),
            Color(.sRGB, red: 18 / 255, green: 21 / 255, blue: 27 / 255, opacity: 0.96),
        ],
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
        static let small: CGFloat = 8       // PIN digit chip, console toolbar button
        static let chip: CGFloat = 6
    }

    // MARK: - Shadows (approximations of the CSS box-shadows; SwiftUI has no spread)

    /// `0 32px 70px -22px rgba(0,0,0,.75)`.
    enum PanelShadow {
        static let color = Color.black.opacity(0.55)
        static let radius: CGFloat = 26
        static let y: CGFloat = 22
    }

    /// `0 8px 18px -7px rgba(91,107,255,.85)`.
    enum PrimaryButtonShadow {
        static let color = Color(.sRGB, red: 91 / 255, green: 107 / 255, blue: 255 / 255, opacity: 0.85)
        static let radius: CGFloat = 8
        static let y: CGFloat = 7
    }
}

// MARK: - Color hex helper

extension Color {
    /// Builds an sRGB color from a 24-bit `0xRRGGBB` value with optional alpha.
    init(hex: UInt32, alpha: Double = 1) {
        let r = Double((hex >> 16) & 0xFF) / 255
        let g = Double((hex >> 8) & 0xFF) / 255
        let b = Double(hex & 0xFF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}

// MARK: - Font registration + helpers

/// Registers the bundled IBM Plex ttf faces with Core Text at launch and records
/// whether they resolved, so `Font.plexSans` / `Font.plexMono` can fall back to
/// the system font when the resource bundle is absent (e.g. an unbundled build).
enum PlexFonts {

    /// Exact PostScript names of the bundled faces (verified against the ttf `name`
    /// tables — note Mono uses abbreviated names and Regular has no suffix).
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

    /// Whether the bundled IBM Plex Sans / Mono faces resolved after registration.
    ///
    /// Modeled as immutable, lazily-initialized `static let`s — Swift runs a global
    /// `let`'s initializer exactly once and thread-safely — rather than mutable
    /// globals. This removes the shared mutable state entirely (no `nonisolated(unsafe)`
    /// escape hatch), and the one-time Core Text registration is performed as a side
    /// effect of computing `sansAvailable`.
    static let sansAvailable: Bool = {
        registerBundledFaces()
        return NSFont(name: Sans.regular, size: 12) != nil
    }()

    static let monoAvailable: Bool = {
        _ = sansAvailable // guarantees registerBundledFaces() has already run
        return NSFont(name: Mono.regular, size: 12) != nil
    }()

    /// Eagerly triggers the one-time registration at launch (optional — the
    /// availability flags trigger it lazily on first use too). Called from
    /// `DeskLinkApp.init`.
    static func register() {
        _ = sansAvailable
        _ = monoAvailable
    }

    /// Registers every bundled `.ttf` with the process font scope, exactly once (from
    /// the `sansAvailable` initializer). `.process("Resources")` may flatten the
    /// directory or preserve "Fonts/", so both are searched. Re-registering an
    /// already-registered URL fails harmlessly.
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
    /// IBM Plex Sans at the given size/weight, falling back to the system UI font.
    static func plexSans(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        guard PlexFonts.sansAvailable else {
            return .system(size: size, weight: weight)
        }
        return .custom(PlexFonts.sansName(for: weight), fixedSize: size)
    }

    /// IBM Plex Mono at the given size/weight, falling back to a monospaced system font.
    static func plexMono(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        guard PlexFonts.monoAvailable else {
            return .system(size: size, weight: weight, design: .monospaced)
        }
        return .custom(PlexFonts.monoName(for: weight), fixedSize: size)
    }
}
