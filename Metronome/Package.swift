// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "Metronome",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "Metronome", path: "Sources/Metronome")
    ]
)
