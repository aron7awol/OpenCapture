import Foundation
import XCTest
@testable import OpenCaptureCore

final class CaptureArchiveStoreTests: XCTestCase {
    func testUnconfirmedAndOptedOutOriginalsStayUntilConfirmedCleanup() throws {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: base) }
        let store = CaptureArchiveStore(root: base.appendingPathComponent("Captures"))
        let id = UUID().uuidString.lowercased(), directory = try store.create(id)
        let original = directory.appendingPathComponent("frame-0001.dng")
        try Data("original".utf8).write(to: original)
        try Data("{}".utf8).write(to: directory.appendingPathComponent("manifest.json"))
        XCTAssertThrowsError(try store.completeUpload(id, confirmation: Data("{}".utf8), deleteAfter: true))
        XCTAssertTrue(FileManager.default.fileExists(atPath: original.path))
        let acknowledgement = try JSONSerialization.data(withJSONObject: ["protocolVersion": 1, "id": id, "status": "complete"])
        try store.completeUpload(id, confirmation: acknowledgement, deleteAfter: false)
        XCTAssertTrue(FileManager.default.fileExists(atPath: original.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent("uploaded.json").path))
        try store.completeUpload(id, confirmation: acknowledgement, deleteAfter: true)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
        XCTAssertThrowsError(try store.create(id))
    }
    func testDeletionKeepsReplayProtectionAndCannotEscapeArchive() throws {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: base) }
        let store = CaptureArchiveStore(root: base.appendingPathComponent("Captures"))
        let id = UUID().uuidString.lowercased(), other = UUID().uuidString.lowercased()
        let directory = try store.create(id), survivor = try store.create(other)
        try Data("original".utf8).write(to: directory.appendingPathComponent("frame-0001.dng"))
        try store.delete(id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: survivor.path))
        XCTAssertThrowsError(try store.create(id))
        XCTAssertThrowsError(try store.delete("../Captures"))
        let linkedID = UUID().uuidString.lowercased()
        try FileManager.default.createSymbolicLink(at: store.root.appendingPathComponent(linkedID), withDestinationURL: survivor)
        XCTAssertThrowsError(try store.delete(linkedID))
        XCTAssertTrue(FileManager.default.fileExists(atPath: survivor.path))
    }
    func testOldArchivesGainTombstoneAndConfirmationMustMatch() throws {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: base) }
        let store = CaptureArchiveStore(root: base.appendingPathComponent("Captures"))
        let id = UUID().uuidString.lowercased()
        try FileManager.default.createDirectory(at: store.root.appendingPathComponent(id), withIntermediateDirectories: true)
        try store.delete(id)
        XCTAssertThrowsError(try store.create(id))
        func ack(_ version: Int = 1, _ job: String? = nil, _ status: String = "complete") throws -> Data {
            try JSONSerialization.data(withJSONObject: ["protocolVersion": version, "id": job ?? id, "status": status])
        }
        XCTAssertNoThrow(try CaptureArchiveStore.confirmUpload(ack(), id: id))
        XCTAssertThrowsError(try CaptureArchiveStore.confirmUpload(ack(2), id: id))
        XCTAssertThrowsError(try CaptureArchiveStore.confirmUpload(ack(1, "different"), id: id))
        XCTAssertThrowsError(try CaptureArchiveStore.confirmUpload(ack(1, nil, "running"), id: id))
        XCTAssertThrowsError(try CaptureArchiveStore.confirmUpload(Data("{}".utf8), id: id))
    }
}
