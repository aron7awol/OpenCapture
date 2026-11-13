import Foundation

public struct CameraServerError: LocalizedError {
    public let status: Int
    public let message: String
    public init(_ status: Int, _ message: String) { self.status = status; self.message = message }
    public var errorDescription: String? { message }
}

public struct CameraServerResult {
    public let data: Data
    public let files: [String: URL]
    public init(data: Data, files: [String: URL] = [:]) { self.data = data; self.files = files }
}

public struct CameraJobState: Encodable {
    public let protocolVersion = 1
    public let id: String
    public var status: String
    public var message: String
    public var resultSha256: String?
    public var cameraReady = false
    public var streaming: OriginalStreamState? = nil
}

/// Main-actor ownership serializes HTTP mutations with camera/UI lifecycle changes.
@MainActor public final class CameraServerSession {
    private final class Job {
        let request: CaptureRequest
        var state: CameraJobState
        var result: CameraServerResult?
        var stream: OriginalStream?
        var writing = true
        init(_ request: CaptureRequest) {
            self.request = request
            state = CameraJobState(id: request.id, status: "running", message: "Capturing")
        }
    }
    public let root: URL
    public private(set) var owner: String?
    private var touched = 0.0
    private var jobs: [String: Job] = [:]
    private var order: [String] = []
    private var task: Task<Void, Never>?
    private var ending: Task<Void, Error>?
    public private(set) var switching = false
    private let capture: (CaptureRequest) async throws -> CameraServerResult
    private let streamCapture: ((CaptureRequest,OriginalStream) async throws -> CameraServerResult)?
    private let hash: (Data) -> String
    private let clock: () -> Double

    public init(root: URL, hash: @escaping (Data) -> String,
                clock: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime },
                streamCapture: ((CaptureRequest,OriginalStream) async throws -> CameraServerResult)? = nil,
                capture: @escaping (CaptureRequest) async throws -> CameraServerResult) throws {
        self.root = root; self.hash = hash; self.clock = clock; self.capture = capture
        self.streamCapture = streamCapture
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try clearFiles()
    }
    private func clearFiles() throws {
        for directory in try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) {
            if UUID(uuidString: directory.lastPathComponent)?.uuidString.lowercased() == directory.lastPathComponent {
                try CaptureArchiveStore(root: root).delete(directory.lastPathComponent)
            }
        }
    }
    public var latest: CameraJobState? { order.last.flatMap { try? state($0) } }
    public var cameraReady: Bool { !switching && task == nil }
    public var pending: Bool { switching || task != nil || jobs.values.contains { $0.state.status == "complete" } }
    public func connect(id: String, client: String) async throws {
        try await expire()
        guard ending == nil, !switching else { throw CameraServerError(503, "Camera is stopping or changing lenses") }
        guard UUID(uuidString: id)?.uuidString.lowercased() == id, (1...80).contains(client.count) else {
            throw CameraServerError(400, "Invalid connection request")
        }
        guard owner == nil || owner == id else { throw CameraServerError(409, "Another PC is connected") }
        owner = id; touched = clock()
    }
    public func authorize(_ id: String?) async throws {
        try await expire()
        guard let id, owner == id, ending == nil else { throw CameraServerError(409, "No active session; connect again") }
        touched = clock()
    }
    public func expire() async throws { if owner != nil, clock() - touched > 120 { try await end() } }
    public func end() async throws {
        if let ending { return try await ending.value }
        owner = nil
        let active = task
        active?.cancel()
        let cleanup = Task { @MainActor in
            await active?.value
            try clearFiles()
            jobs.removeAll(); order.removeAll()
        }
        ending = cleanup
        defer { ending = nil }
        try await cleanup.value
    }
    public func select<T>(owner id: String?, action: () async throws -> T) async throws -> T {
        try await authorize(id)
        guard !pending else { throw CameraServerError(409, "Finish capture and transfer before changing lenses") }
        switching = true
        defer { switching = false }
        let result = try await action()
        try await authorize(id)
        return result
    }
    public func submit(_ request: CaptureRequest) throws -> CameraJobState {
        try request.validate()
        guard request.streaming == nil || streamCapture != nil else { throw CameraServerError(400,"Streaming unavailable") }
        if let previous = jobs[request.id] {
            guard previous.request == request else { throw CameraServerError(409, "Capture ID has another request") }
            return try state(request.id)
        }
        guard owner != nil, ending == nil, !pending else { throw CameraServerError(409, "Camera is not available for a new capture") }
        guard jobs.count < 1000 else { throw CameraServerError(409, "Session job limit reached; reconnect") }
        let ledger = root.deletingLastPathComponent().appendingPathComponent("ServerJobHistory", isDirectory: true)
        try FileManager.default.createDirectory(at: ledger, withIntermediateDirectories: true)
        do { try Data().write(to: ledger.appendingPathComponent(request.id), options: .withoutOverwriting) }
        catch { throw CameraServerError(409, "Capture ID was already used; do not replay it") }
        let job = Job(request); jobs[request.id] = job; order.append(request.id)
        if request.streaming != nil { job.stream = OriginalStream(request:request,directory:root.appendingPathComponent(request.id)) }
        task = Task { @MainActor in
            defer {
                job.writing = false
                task = nil
                if job.state.status == "acknowledged" { try? clean(job) }
            }
            do {
                try Task.checkCancellation()
                let result: CameraServerResult
                if let stream = job.stream, let streamCapture { result = try await streamCapture(request,stream) }
                else { result = try await capture(request) }
                try Task.checkCancellation()
                if job.stream != nil && job.state.status != "running" { return }
                guard result.data.count <= 1_048_576 else { throw CameraServerError(413, "Result too large") }
                var total = 0
                let directory = root.appendingPathComponent(request.id).resolvingSymlinksInPath().standardizedFileURL
                for (name, file) in result.files {
                    let derived = request.averaging != nil && ["average.f32","average-clips.u8"].contains(name)
                    guard derived || name.range(of: "^frame-[0-9]{4}\\.(dng|jpg|json|preview\\.jpg)$", options: .regularExpression) != nil,
                          file.lastPathComponent == name, file.resolvingSymlinksInPath().deletingLastPathComponent().standardizedFileURL == directory else {
                        throw CameraServerError(400, "Invalid capture file")
                    }
                    let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard (1...134_217_728).contains(size) else { throw CameraServerError(413, "Capture file size invalid") }
                    total += size
                }
                guard total <= 4 * 1024 * 1024 * 1024 else { throw CameraServerError(413, "Capture too large") }
                job.result = result; job.state.resultSha256 = hash(result.data)
                job.state.status = "complete"; job.state.message = "Waiting for transfer"
            } catch {
                if job.stream?.frozen == true && !Task.isCancelled { return }
                job.state.status = Task.isCancelled ? "cancelled" : "failed"
                job.state.message = String(error.localizedDescription.prefix(256))
                do { try clean(job) } catch { job.state.message = "Temporary-file cleanup failed" }
            }
        }
        return try state(request.id)
    }
    private func job(_ id: String) throws -> Job {
        guard let job = jobs[id] else { throw CameraServerError(404, "Unknown capture") }
        return job
    }
    public func state(_ id: String) throws -> CameraJobState {
        let job = try job(id);var state=job.state
        state.cameraReady=cameraReady;state.streaming=job.stream?.state
        return state
    }
    public func verdict(_ id: String, _ value: StreamVerdict) throws -> CameraJobState {
        let job=try job(id)
        guard ["running","complete","acknowledged"].contains(job.state.status), let stream=job.stream else {
            throw CameraServerError(409,"Not an active streamed capture")
        }
        try stream.verdict(value)
        if job.state.status=="running",let result=stream.result {
            job.result=result;job.state.resultSha256=hash(result.data)
            job.state.status="complete";job.state.message="Selection frozen; waiting for receipt"
        }
        return try state(id)
    }
    public func file(_ id: String, _ name: String) throws -> URL {
        let job=try job(id)
        if job.state.status=="running",let file=job.stream?.file(name) { return file }
        if job.state.status=="complete",let file=job.result?.files[name] { return file }
        throw CameraServerError(404,"Frame not published")
    }
    public func progress(_ id: String, _ message: String) {
        if let job = jobs[id], job.state.status == "running" { job.state.message = String(message.prefix(256)) }
    }
    public func result(_ id: String) throws -> CameraServerResult {
        let job = try job(id)
        guard job.state.status == "complete", let result = job.result else { throw CameraServerError(409, "Capture unavailable") }
        return result
    }
    private func clean(_ job: Job) throws {
        if FileManager.default.fileExists(atPath: root.appendingPathComponent(job.request.id).path) {
            try CaptureArchiveStore(root: root).delete(job.request.id)
        }
        job.result = nil
    }
    public func acknowledge(_ id: String, hash: String) throws -> CameraJobState {
        let job = try job(id)
        guard ["complete", "acknowledged"].contains(job.state.status), hash == job.state.resultSha256 else {
            throw CameraServerError(409, "Receipt does not match result")
        }
        if !job.writing { try clean(job) }
        job.state.status = "acknowledged"; job.state.message = "Received by Windows"
        return try state(id)
    }
    public func cancel(_ id: String) async throws -> CameraJobState {
        let job = try job(id)
        // A stream can freeze its result while the extra exposure still owns
        // the writer. Cancellation must wait for that writer before deletion.
        if job.writing { let active = task; active?.cancel(); await active?.value }
        try clean(job); job.state.status = "cancelled"; job.state.message = "Cancelled"
        return try state(id)
    }
}
