import Foundation
import XCTest
@testable import OpenCaptureHTTP

@MainActor final class LANHTTPServerTests: XCTestCase {
    func testFilesAndMemoryResponsesHaveExactBytesAndRetry() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let small = Data("{\"metadata\":\"original sidecar\"}".utf8)
        let block = Data((0..<65536).map { UInt8(truncatingIfNeeded: $0) })
        var large = Data()
        for _ in 0..<129 { large.append(block) }
        large.append(contentsOf: [9, 8, 7]) // Non-aligned final chunk.
        let files = ["small": small, "large": large, "empty": Data()]
        for (name, data) in files { try data.write(to: root.appendingPathComponent(name)) }
        let server = LANHTTPServer()
        do {
            try await server.start(address: "127.0.0.1") { request in
                if request.path == "/memory" { return CameraHTTPResponse(data: small) }
                return CameraHTTPResponse(type: "application/octet-stream",
                    file: root.appendingPathComponent(String(request.path.dropFirst())))
            }
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = 15
            let session = URLSession(configuration: configuration)
            defer { session.invalidateAndCancel() }
            for name in ["small", "large", "empty", "large", "memory", "small"] {
                let expected = name == "memory" ? small : files[name]!
                let (data, response) = try await session.data(from: URL(string: "http://127.0.0.1:8767/" + name)!)
                let http = try XCTUnwrap(response as? HTTPURLResponse)
                XCTAssertEqual(http.statusCode, 200)
                XCTAssertEqual(http.value(forHTTPHeaderField: "Content-Length"), String(expected.count))
                XCTAssertEqual(data, expected)
            }
            // Opening a missing file must not take down the listener.
            do {
                _ = try await session.data(from: URL(string: "http://127.0.0.1:8767/missing")!)
                XCTFail("Missing file unexpectedly produced a response")
            } catch { }
            let (data, _) = try await session.data(from: URL(string: "http://127.0.0.1:8767/small")!)
            XCTAssertEqual(data, small)
            await server.stop()
        } catch {
            await server.stop()
            throw error
        }
    }
}
