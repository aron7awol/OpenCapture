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

// The iOS HTTP implementation used to be absent from `swift test`. Exercise the
// same source on macOS; device tests remain necessary for iOS syscall policies.
#if os(macOS)
package.dependencies.append(.package(url: "https://github.com/apple/swift-nio.git", exact: "2.105.0"))
package.targets.append(.target(name: "OpenCaptureHTTP", dependencies: [
    .product(name: "NIOCore", package: "swift-nio"),
    .product(name: "NIOPosix", package: "swift-nio"),
    .product(name: "NIOHTTP1", package: "swift-nio")
], path: "Sources", sources: ["LANHTTPServer.swift"]))
package.targets.append(.testTarget(name: "OpenCaptureHTTPTests", dependencies: ["OpenCaptureHTTP"], path: "HTTPTests"))
#endif
