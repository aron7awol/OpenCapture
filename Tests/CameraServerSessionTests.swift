import Foundation
import XCTest
@testable import OpenCaptureCore

@MainActor final class CameraServerSessionTests: XCTestCase {
    private func temporaryRoot() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("Transfers")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
        return root
    }
    private func wait(_ condition: () throws -> Bool) async throws {
        for _ in 0..<200 {
            if try condition() { return }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTFail("Session operation timed out")
    }
    func testRetryReceiptAndTemporaryCleanup() async throws {
        let root = try temporaryRoot()
        var captures = 0
        let session = try CameraServerSession(root: root, hash: { _ in String(repeating: "a", count: 64) }) { request in
            captures += 1
            let directory = try CaptureArchiveStore(root: root).create(request.id)
            let image = directory.appendingPathComponent("frame-0001.dng")
            try Data("original".utf8).write(to: image)
            return CameraServerResult(data: Data("{}".utf8), files: ["frame-0001.dng": image])
        }
        let owner = UUID().uuidString.lowercased()
        try await session.connect(id: owner, client: "Test PC")
        var request = CaptureRequest(); request.frameCount = 1
        _ = try session.submit(request)
        try await wait { try session.state(request.id).status == "complete" }
        _ = try session.submit(request); XCTAssertEqual(captures, 1)
        XCTAssertThrowsError(try session.acknowledge(request.id, hash: "bad"))
        _ = try session.acknowledge(request.id, hash: String(repeating: "a", count: 64))
        _ = try session.acknowledge(request.id, hash: String(repeating: "a", count: 64))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(request.id).path))
        XCTAssertEqual(try session.submit(request).status, "acknowledged")
        try await session.end()
        try await session.connect(id: UUID().uuidString.lowercased(), client: "New PC")
        XCTAssertThrowsError(try session.submit(request))
    }
    func testExpiryCancelsWriterAndDoesNotDeleteLegacyArchive() async throws {
        let root = try temporaryRoot()
        let legacy = root.deletingLastPathComponent().appendingPathComponent("OldCapture.dng")
        try Data("old".utf8).write(to: legacy)
        var clock = 0.0
        var writing = false
        let session = try CameraServerSession(root: root, hash: { _ in "test" }, clock: { clock }) { request in
            let directory = try CaptureArchiveStore(root: root).create(request.id)
            writing = true
            do { try await Task.sleep(nanoseconds: 60_000_000_000) }
            catch {
                XCTAssertTrue(FileManager.default.fileExists(atPath: directory.path))
                writing = false; throw error
            }
            return CameraServerResult(data: Data())
        }
        let owner = UUID().uuidString.lowercased()
        try await session.connect(id: owner, client: "PC")
        do { try await session.connect(id: UUID().uuidString.lowercased(), client: "Other"); XCTFail("Ownership was replaced") }
        catch { XCTAssertEqual((error as? CameraServerError)?.status, 409) }
        let request = CaptureRequest(); _ = try session.submit(request)
        try await wait { writing }
        clock = 121; try await session.expire()
        XCTAssertNil(session.owner); XCTAssertFalse(writing)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(request.id).path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: legacy.path))
    }
}
