// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "DeskLink",
    platforms: [
        .macOS(.v14),
    ],
    targets: [
        .target(
            name: "CGVirtualDisplayBridge",
            path: "Sources/CGVirtualDisplayBridge",
            publicHeadersPath: "include",
            linkerSettings: [
                .linkedFramework("CoreGraphics"),
                .linkedFramework("AppKit"),
            ]
        ),
        .executableTarget(
            name: "DeskLink",
            dependencies: ["CGVirtualDisplayBridge"],
            path: "Sources",
            exclude: ["CGVirtualDisplayBridge"],
            resources: [
                // Bundled IBM Plex Sans/Mono ttf fonts (Sources/Resources/Fonts).
                // Registered at launch via CTFontManager; UI falls back to the
                // system font if this bundle is missing or registration fails.
                .process("Resources"),
            ],
            linkerSettings: [
                .linkedFramework("CoreGraphics"),
                .linkedFramework("ScreenCaptureKit"),
                .linkedFramework("VideoToolbox"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("CoreVideo"),
                .linkedFramework("Network"),
                .linkedFramework("ApplicationServices"),
            ]
        ),
        .testTarget(
            name: "DeskLinkTests",
            dependencies: ["DeskLink"],
            path: "Tests"
        ),
    ]
)
