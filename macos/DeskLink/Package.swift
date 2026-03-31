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
            linkerSettings: [
                .linkedFramework("CoreGraphics"),
            ]
        ),
        .testTarget(
            name: "DeskLinkTests",
            dependencies: ["DeskLink"],
            path: "Tests"
        ),
    ]
)
