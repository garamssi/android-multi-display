// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "DeskLink",
    platforms: [
        .macOS(.v14),
    ],
    targets: [
        .executableTarget(
            name: "DeskLink",
            path: "Sources"
        ),
        .testTarget(
            name: "DeskLinkTests",
            dependencies: ["DeskLink"],
            path: "Tests"
        ),
    ]
)
