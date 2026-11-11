import Foundation

public struct CaptureArchiveStore: Sendable {
    public let root: URL
    public init(root: URL) { self.root = root }
    private var ledger: URL { root.deletingLastPathComponent().appendingPathComponent("CaptureHistory", isDirectory: true) }
    private func directory(_ id: String) throws -> URL {
        guard UUID(uuidString: id)?.uuidString.lowercased() == id else { throw CaptureError.invalid("Invalid capture ID") }
        let directory = root.appendingPathComponent(id, isDirectory: true)
        let properties = try? directory.resourceValues(forKeys: [.isSymbolicLinkKey])
        guard properties?.isSymbolicLink != true,
              directory.resolvingSymlinksInPath().deletingLastPathComponent().standardizedFileURL.path == root.resolvingSymlinksInPath().standardizedFileURL.path else {
            throw CaptureError.invalid("Capture path is outside the archive")
        }
        return directory
    }
    public func create(_ id: String) throws -> URL {
        let directory = try directory(id)
        let files = FileManager.default
        guard !files.fileExists(atPath: directory.path), !files.fileExists(atPath: ledger.appendingPathComponent(id).path) else {
            throw CaptureError.invalid("Capture ID was already used; originals cannot be replayed")
        }
        try files.createDirectory(at: root, withIntermediateDirectories: true)
        try files.createDirectory(at: ledger, withIntermediateDirectories: true)
        try Data().write(to: ledger.appendingPathComponent(id), options: .withoutOverwriting)
        try files.createDirectory(at: directory, withIntermediateDirectories: false)
        return directory
    }
    public func delete(_ id: String) throws {
        let directory = try directory(id)
        let files = FileManager.default
        guard files.fileExists(atPath: directory.path) else { throw CaptureError.invalid("Capture no longer exists") }
        try files.createDirectory(at: ledger, withIntermediateDirectories: true)
        let marker = ledger.appendingPathComponent(id)
        if !files.fileExists(atPath: marker.path) { try Data().write(to: marker, options: .withoutOverwriting) }
        try files.removeItem(at: directory)
    }
    public func completeUpload(_ id: String, confirmation: Data, deleteAfter: Bool) throws {
        try Self.confirmUpload(confirmation, id: id)
        let directory = try directory(id)
        guard FileManager.default.fileExists(atPath: directory.appendingPathComponent("manifest.json").path) else {
            throw CaptureError.invalid("Capture manifest is missing; phone files retained")
        }
        try confirmation.write(to: directory.appendingPathComponent("uploaded.json"), options: .atomic)
        if deleteAfter { try delete(id) }
    }
    public static func confirmUpload(_ data: Data, id: String) throws {
        struct Confirmation: Decodable { let protocolVersion: Int; let id: String; let status: String }
        let confirmation = try JSONDecoder().decode(Confirmation.self, from: data)
        guard confirmation.protocolVersion == 1, confirmation.id == id, confirmation.status == "complete" else {
            throw CaptureError.transport("Windows did not confirm this complete upload; phone originals retained")
        }
    }
}
