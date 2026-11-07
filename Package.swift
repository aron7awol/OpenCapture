// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "OpenCaptureCore",
    platforms: [.macOS(.v13), .iOS(.v17)],
    products: [.library(name: "OpenCaptureCore", targets: ["OpenCaptureCore"])],
    targets: [
        .target(name: "OpenCaptureCore", path: "Core"),
        .testTarget(name: "OpenCaptureCoreTests", dependencies: ["OpenCaptureCore"], path: "Tests")
    ]
)
