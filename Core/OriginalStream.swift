import Foundation

public struct StreamVerdict: Codable, Equatable, Sendable {
    public let index: Int
    public let sha256: String
    public let accepted: Bool
    public let reason: String
}
public struct StreamRejection: Codable, Sendable {
    public let index: Int
    public let reason: String
}
public struct StreamSelection: Codable, Sendable {
    public let mode: String
    public let status: String
    public let targetFrames: Int
    public let attemptedFrames: Int
    public let acceptedIndices: [Int]
    public let rejected: [StreamRejection]
}
public struct OriginalStreamState: Encodable {
    public let mode = "originals-v1"
    public let frames: [FrameRecord]
    public let verdictCount: Int
    public let frozen: Bool
}

/// Camera remains sequential; the controller may transfer the previous frame
/// during the next exposure. Frozen results do not wait for discarded exposures.
@MainActor public final class OriginalStream {
    private let request: CaptureRequest
    private let directory: URL
    private var manifest: CaptureManifest?
    private var frames: [FrameRecord] = []
    private var files: [String: URL] = [:]
    private var verdicts: [StreamVerdict] = []
    private var lastProgress = ProcessInfo.processInfo.systemUptime
    private let maximumBytes: Int
    private var publishedBytes = 0
    public private(set) var result: CameraServerResult?
    public var limit: Int { request.frameCount * 2 }
    public var frozen: Bool { result != nil }
    public var state: OriginalStreamState { OriginalStreamState(frames: frames, verdictCount: verdicts.count, frozen: frozen) }
    public init(request: CaptureRequest, directory: URL, maximumBytes: Int = 4*1024*1024*1024) {
        precondition((1...4*1024*1024*1024).contains(maximumBytes))
        self.maximumBytes = maximumBytes
        self.request = request; self.directory = directory.resolvingSymlinksInPath().standardizedFileURL
    }
    public func begin(camera: CameraCapabilities, started: String, processing: String) {
        manifest = CaptureManifest(request: request, camera: camera, frames: [], startedAt: started, completedAt: started, processing: processing)
    }
    private func live() throws {
        try Task.checkCancellation()
        guard ProcessInfo.processInfo.systemUptime-lastProgress < 240 else {
            throw CameraServerError(408,"Controller frame verdict timed out")
        }
    }
    public func next() async throws -> Bool {
        while !frozen && frames.count-verdicts.count >= 2 {
            try live(); try await Task.sleep(nanoseconds:50_000_000)
        }
        try live(); return !frozen && frames.count < limit
    }
    public func publish(_ frame: FrameRecord) throws {
        let receipts = [frame.file,frame.metadataFile] + (frame.previewFile.map { [$0] } ?? [])
        guard frame.index==frames.count+1, frames.count<limit, frozen || frames.count-verdicts.count<2,
              Set(receipts.map(\.name)).count == receipts.count else {
            throw CameraServerError(409,"Stream publication exceeded lookahead")
        }
        for receipt in receipts {
            let file=directory.appendingPathComponent(receipt.name).resolvingSymlinksInPath().standardizedFileURL
            guard receipt.name.range(of:"^frame-[0-9]{4}\\.(dng|jpg|json|preview\\.jpg)$",options:.regularExpression) != nil,
                  file.deletingLastPathComponent()==directory, files[receipt.name]==nil,
                  (1...134_217_728).contains(receipt.bytes),
                  try file.resourceValues(forKeys:[.fileSizeKey]).fileSize==receipt.bytes else {
                throw CameraServerError(400,"Invalid streamed file")
            }
        }
        if frozen {
            for receipt in receipts { try? FileManager.default.removeItem(at:directory.appendingPathComponent(receipt.name)) }
            return
        }
        let bytes = receipts.reduce(0) { $0+$1.bytes }
        guard bytes <= maximumBytes-publishedBytes else { throw CameraServerError(413,"Stream exceeds storage limit") }
        for receipt in receipts { files[receipt.name]=directory.appendingPathComponent(receipt.name) }
        publishedBytes += bytes
        frames.append(frame);lastProgress=ProcessInfo.processInfo.systemUptime
    }
    public func file(_ name: String) -> URL? { files[name] }
    public func verdict(_ value: StreamVerdict) throws {
        guard (1...80).contains(value.reason.count) else { throw CameraServerError(400,"Invalid verdict reason") }
        if (1...max(1,verdicts.count)).contains(value.index), value.index<=verdicts.count {
            guard verdicts[value.index-1]==value else { throw CameraServerError(409,"Conflicting frame verdict") }
            return
        }
        guard !frozen, value.index==verdicts.count+1, value.index<=frames.count,
              value.sha256==frames[value.index-1].file.sha256 else { throw CameraServerError(409,"Wrong or late frame verdict") }
        verdicts.append(value);lastProgress=ProcessInfo.processInfo.systemUptime
        let accepted=verdicts.filter(\.accepted).map(\.index)
        guard accepted.count==request.frameCount || verdicts.count==limit else { return }
        guard var manifest else { throw CameraServerError(409,"Stream has not started") }
        manifest.frames=Array(frames.prefix(verdicts.count))
        manifest.completedAt=ISO8601DateFormatter().string(from:Date())
        manifest.streaming=StreamSelection(mode:"originals-v1",status:accepted.count==request.frameCount ? "complete":"insufficient",
            targetFrames:request.frameCount,attemptedFrames:verdicts.count,acceptedIndices:accepted,
            rejected:verdicts.filter { !$0.accepted }.map { StreamRejection(index:$0.index,reason:$0.reason) })
        let names=Set(manifest.frames.flatMap { [$0.file.name,$0.metadataFile.name] + ($0.previewFile.map { [$0.name] } ?? []) })
        let selected=files.filter { names.contains($0.key) }
        guard manifest.frames.reduce(0, { $0+$1.file.bytes+$1.metadataFile.bytes+($1.previewFile?.bytes ?? 0) }) <= 4*1024*1024*1024 else {
            throw CameraServerError(413,"Stream exceeds storage limit")
        }
        let data=try JSONEncoder().encode(manifest)
        guard data.count<=1_048_576 else { throw CameraServerError(413,"Result too large") }
        try data.write(to:directory.appendingPathComponent("manifest.json"),options:.atomic)
        result=CameraServerResult(data:data,files:selected)
    }
    public func finish() async throws -> CameraServerResult {
        while result==nil { try live();try await Task.sleep(nanoseconds:50_000_000) }
        return result!
    }
}
