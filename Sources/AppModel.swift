import Foundation
import SwiftUI
import AVFoundation
import CryptoKit

private struct CameraServerInfo: Encodable {
    let protocolVersion = 1
    let role = "camera"
    let deviceID: String
    let name: String
    let capabilities: CameraCapabilities
    let cameras: [CameraChoice]
    let session: Session
    struct Session: Encodable { let connected: Bool; let job: CameraJobState? }
}

@MainActor final class AppModel: NSObject, ObservableObject, NetServiceDelegate {
    let camera = CameraService()
    @Published private(set) var status = "Opening camera"
    @Published private(set) var cameraName = "OpenCapture"
    @Published private(set) var enabled = true
    @Published private(set) var capabilities: CameraCapabilities?
    @Published var update: AppUpdate?
    @Published var updateStatus = ""
    @Published var checkingUpdate = false
    private var foregrounded = false
    private var configuring = false
    private var server: LANHTTPServer?
    private var advertisement: NetService?
    private var address: String?
    private var transfers: CameraServerSession?
    private var serviceTask: Task<Void, Never>?
    private var shutdownTask: Task<Void, Never>?
    var captureRoot: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenCapture", isDirectory: true).appendingPathComponent("Transfers", isDirectory: true)
    }

    func foreground() async {
        foregrounded = true
        if let shutdownTask { await shutdownTask.value }
        guard foregrounded, enabled else { return }
        UIApplication.shared.isIdleTimerDisabled = true
        guard serviceTask == nil else { return }
        serviceTask = Task { @MainActor in
            defer { serviceTask = nil }
            do {
                if transfers == nil {
                    transfers = try CameraServerSession(root: captureRoot,
                        hash: { SHA256.hash(data: $0).map { String(format: "%02x", $0) }.joined() },
                        capture: { [weak self] request in
                            guard let self else { throw CancellationError() }
                            return try await self.capture(request)
                        })
                }
                if capabilities == nil { try await configure(nil) }
                while !Task.isCancelled && foregrounded && enabled {
                    try await transfers?.expire()
                    let ip = LANHTTPServer.wifiAddress()
                    if address != ip { try await stopNetwork() }
                    if let ip, server == nil, !configuring { try await startNetwork(ip) }
                    if server == nil { status = "Waiting for Wi-Fi" }
                    else if transfers?.owner == nil { status = "Ready at \(address ?? ""):8767" }
                    else if let job = transfers?.latest, job.status != "running" {
                        status = job.status == "acknowledged" ? "Connected. Ready" : job.message
                    }
                    try await Task.sleep(nanoseconds: 1_000_000_000)
                }
            } catch { if !Task.isCancelled { status = error.localizedDescription } }
        }
    }
    func inactive() { UIApplication.shared.isIdleTimerDisabled = false }
    func background() {
        foregrounded = false; beginShutdown()
    }
    func toggle() async {
        enabled.toggle()
        if enabled { await foreground() }
        else { beginShutdown() }
    }
    private func beginShutdown() {
        UIApplication.shared.isIdleTimerDisabled = false
        guard shutdownTask == nil else { return }
        serviceTask?.cancel()
        let previous = serviceTask
        shutdownTask = Task { @MainActor in
            defer { shutdownTask = nil }
            await previous?.value
            do { try await stopNetwork(); status = "Stopped" }
            catch { status = "Temporary-file cleanup failed: \(error.localizedDescription)" }
            camera.stop(); capabilities = nil
        }
    }
    private func configure(_ id: String?) async throws {
        guard foregrounded, enabled, !configuring else { throw CameraServerError(409, "Camera is not available") }
        configuring = true
        defer { configuring = false }
        let caps = try await camera.configure(cameraID: id)
        guard foregrounded, enabled else { camera.stop(); throw CancellationError() }
        capabilities = caps
        cameraName = CameraService.cameras.first(where: { $0.id == caps.cameraID })?.name ?? caps.name
    }
    private func startNetwork(_ ip: String) async throws {
        let listener = LANHTTPServer()
        do {
            try await listener.start(address: ip) { [weak self] request in
                guard let self else { return CameraHTTPResponse(code: 503) }
                return await self.route(request)
            }
            guard foregrounded, enabled, !Task.isCancelled else { await listener.stop(); throw CancellationError() }
            server = listener; address = ip
            let service = NetService(domain: "local.", type: "_oc-camera._tcp.", name: "OpenCapture \(UIDevice.current.name)", port: 8767)
            service.delegate = self
            service.setTXTRecord(NetService.data(fromTXTRecord: ["protocol": Data("1".utf8), "role": Data("camera".utf8),
                "auth": Data("none".utf8), "deviceID": Data((capabilities?.deviceID ?? "").utf8)]))
            advertisement = service; service.publish()
        } catch { await listener.stop(); throw error }
    }
    private func stopNetwork() async throws {
        advertisement?.stop(); advertisement = nil
        let previous = server; server = nil; address = nil
        await previous?.stop()
        try await transfers?.end()
    }
    nonisolated func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        Task { @MainActor in self.status = "Discovery unavailable; \(self.address ?? "Wi-Fi"):8767" }
    }
    private func info() throws -> CameraServerInfo {
        guard let capabilities, let deviceID = capabilities.deviceID, !configuring else { throw CameraServerError(503, "Camera is not ready") }
        return CameraServerInfo(deviceID: deviceID, name: UIDevice.current.model, capabilities: capabilities,
            cameras: CameraService.cameras, session: .init(connected: transfers?.owner != nil, job: transfers?.latest))
    }
    private func object(_ data: Data, keys: Set<String>) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any], Set(object.keys) == keys else {
            throw CameraServerError(400, "Invalid request fields")
        }
        return object
    }
    private func route(_ request: CameraHTTPRequest) async -> CameraHTTPResponse {
        do {
            guard foregrounded, enabled, let transfers else { throw CameraServerError(503, "Camera server stopped") }
            if request.method == "GET", request.path == "/oc/v1/info" { return try .json(info()) }
            if request.method == "POST", request.path == "/oc/v1/connect" {
                let body = try object(request.data, keys: ["protocolVersion", "sessionID", "clientName"])
                guard body["protocolVersion"] as? Int == 1, let id = body["sessionID"] as? String, let name = body["clientName"] as? String else {
                    throw CameraServerError(400, "Invalid connection request")
                }
                try await transfers.connect(id: id, client: name); status = "Connected to \(name)"
                struct Connected: Encodable { let protocolVersion = 1; let sessionID: String }
                return try .json(Connected(sessionID: id))
            }
            try await transfers.authorize(request.session)
            if request.method == "GET", request.path == "/oc/v1/state" { return try .json(info()) }
            if request.method == "POST", request.path == "/oc/v1/disconnect" {
                try await transfers.end(); status = "Ready"
                return try .json(["protocolVersion": 1])
            }
            if request.method == "POST", request.path == "/oc/v1/camera" {
                let body = try object(request.data, keys: ["cameraID"])
                guard let id = body["cameraID"] as? String, CameraService.cameras.contains(where: { $0.id == id }) else {
                    throw CameraServerError(400, "Unavailable camera")
                }
                return try await transfers.select(owner: request.session) {
                    try await configure(id); return try .json(info())
                }
            }
            if request.method == "POST", request.path == "/oc/v1/jobs" {
                return try .json(transfers.submit(CaptureRequest.decode(request.data)), code: 202)
            }
            if request.method == "GET", request.path == "/oc/v1/preview.jpg" {
                guard let preview = camera.latestPreview(), preview.count <= 2_097_152 else { throw CameraServerError(404, "No preview") }
                return CameraHTTPResponse(type: "image/jpeg", data: preview)
            }
            let path = request.path.components(separatedBy: "/")
            guard (5...7).contains(path.count), Array(path.prefix(4)) == ["", "oc", "v1", "jobs"],
                  UUID(uuidString: path[4])?.uuidString.lowercased() == path[4] else { throw CameraServerError(404, "Unknown endpoint") }
            let id = path[4]
            if request.method == "GET" {
                if path.count == 5 { return try .json(transfers.state(id)) }
                if path.count == 6, path[5] == "result" { return CameraHTTPResponse(data: try transfers.result(id).data) }
                if path.count == 7, path[5] == "files", let file = try transfers.result(id).files[path[6]] {
                    return CameraHTTPResponse(type: "application/octet-stream", file: file)
                }
            } else if path.count == 6 {
                if path[5] == "ack" {
                    let body = try object(request.data, keys: ["resultSha256"])
                    guard let hash = body["resultSha256"] as? String else { throw CameraServerError(400, "Missing result hash") }
                    return try .json(transfers.acknowledge(id, hash: hash))
                }
                if path[5] == "cancel" {
                    let state = try await transfers.cancel(id)
                    return try .json(state)
                }
            }
            throw CameraServerError(404, "Unknown endpoint")
        } catch {
            return (try? .json(["error": String(error.localizedDescription.prefix(256))], code: (error as? CameraServerError)?.status ?? 400)) ?? CameraHTTPResponse(code: 500)
        }
    }

    private func capture(_ request: CaptureRequest) async throws -> CameraServerResult {
        guard let capabilities else { throw CaptureError.unavailable("Camera is not ready") }
        try request.validate()
        guard capabilities.formats.contains(request.format) else { throw CaptureError.unavailable("Capture format unavailable on this lens") }
        if let averaging = request.averaging, !(capabilities.frameAveragingModes ?? []).contains(averaging.mode) {
            throw CaptureError.unavailable("Averaging unavailable on this lens")
        }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        if request.operation == .applyControls {
            status = "Locking controls"
            try await camera.prepare(request)
            let reading = try await camera.reading()
            try Task.checkCancellation()
            let result = CameraControlResult(request: request, camera: capabilities, deviceExposureSeconds: reading.exposure,
                deviceISO: reading.iso, lensPosition: reading.focus, whiteBalance: reading.whiteBalance,
                completedAt: ISO8601DateFormatter().string(from: Date()))
            return CameraServerResult(data: try encoder.encode(result))
        }
        let available = try captureRoot.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
        let reserve: Int64 = request.format == .appleProRAW ? 160_000_000 : 100_000_000
        let attemptLimit = request.frameCount * (request.averaging == nil ? 1 : 2)
        guard available > Int64(attemptLimit) * reserve + 200_000_000 else { throw CaptureError.unavailable("Not enough temporary storage for this burst") }
        let directory = try CaptureArchiveStore(root: captureRoot).create(request.id)
        try encoder.encode(request).write(to: directory.appendingPathComponent("request.json"), options: .atomic)
        let started = ISO8601DateFormatter().string(from: Date())
        status = "Settling and locking controls"
        camera.pausePreview(true)
        defer { camera.pausePreview(false) }
        try await camera.prepare(request)
        var frames: [FrameRecord] = []
        var files: [String: URL] = [:]
        var previousSamples: String?
        var selection = try request.averaging.map { try BayerSelection(target:request.frameCount,policy:$0.emptyFramePolicy) }
        for index in 1...attemptLimit {
            try Task.checkCancellation()
            status = "Frame \(index) of \(request.frameCount)"
            var frame = try await camera.photo(request: request, index: index, directory: directory)
            if request.format == .bayerRAW {
                let file = directory.appendingPathComponent(frame.metadataFile.name)
                if var sidecar = try JSONSerialization.jsonObject(with:Data(contentsOf:file)) as? [String:Any],
                   var diagnostics = sidecar["captureDiagnostics"] as? [String:Any],
                   let samples = (diagnostics["rawBeforeDng"] as? [String:Any])?["sha256"] as? String {
                    if let previousSamples { diagnostics["sameSamplesAsPreviousFrame"] = previousSamples == samples }
                    previousSamples = samples
                    sidecar["captureDiagnostics"] = diagnostics
                    try JSONSerialization.data(withJSONObject:sidecar,options:[.prettyPrinted,.sortedKeys]).write(to:file,options:.atomic)
                    frame.metadataFile = try BayerAverage.receipt(file,media:"application/json")
                }
            }
            frames.append(frame)
            if selection != nil {
                let evidence = try await processing {
                    guard let sidecar = try JSONSerialization.jsonObject(with:Data(contentsOf:directory.appendingPathComponent(frame.metadataFile.name))) as? [String:Any],
                          let diagnostics = sidecar["captureDiagnostics"] as? [String:Any], let fields = diagnostics["normalization"] else {
                        throw CaptureError.invalid("Missing native Bayer normalization")
                    }
                    let layout = try JSONDecoder().decode(BayerLayout.self,from:JSONSerialization.data(withJSONObject:fields))
                    let assessment = (diagnostics["sampleIntegrity"] as? [String:Any])?["assessment"] as? String ?? "incomplete_diagnostics"
                    return try BayerAverage.inspect(file:directory.appendingPathComponent(String(format:"frame-%04d.raw16",index)),layout:layout,integrity:assessment)
                }
                selection!.attempts.append(evidence)
                status = "Checked \(index) attempts; \(selection!.accepted.count) / \(request.frameCount) usable"
                transfers?.progress(request.id,status)
            }
            for file in [frame.file, frame.metadataFile] + (frame.previewFile.map { [$0] } ?? []) {
                files[file.name] = directory.appendingPathComponent(file.name)
            }
            if let selection, !selection.needsMore { break }
        }
        var aggregation: BayerAggregation?
        if let selection, let options = request.averaging {
            transfers?.progress(request.id,"Averaging \(selection.accepted.count) usable RAW frames")
            let originals = frames
            let result = try await processing { () -> (BayerAggregation,[FrameRecord]) in
                var records = originals
                return (try BayerAverage.finalize(selection,options:options,frames:&records,directory:directory),records)
            }
            aggregation = result.0; frames = result.1
            let retained = Set(result.0.retainedOriginalIndices)
            for frame in frames where !retained.contains(frame.index) { files.removeValue(forKey:frame.file.name) }
            for receipt in result.0.files.values { files[receipt.name] = directory.appendingPathComponent(receipt.name) }
        }
        var manifest = CaptureManifest(request: request, camera: capabilities, frames: frames, startedAt: started,
            completedAt: ISO8601DateFormatter().string(from: Date()),
            processing: request.format == .bayerRAW ? "AVFoundation Bayer RAW pixel format; original DNG bytes unchanged" :
                request.format == .appleProRAW ? "AVFoundation Apple ProRAW, maximum advertised dimensions requested; demosaiced RGB, computational processing and possible multi-frame fusion; original lossless DNG unchanged; not a Bayer measurement" :
                "AVFoundation JPEG, \(request.quality.rawValue) priority; processed, not calibrated or guaranteed unsharpened")
        manifest.aggregation = aggregation
        if aggregation != nil { manifest.processing = "Native Bayer buffer; DNG black/white normalization and embedded radial gain; fractional site means and luma moments; no demosaic or geometric warp" }
        let data = try encoder.encode(manifest)
        try data.write(to: directory.appendingPathComponent("manifest.json"), options: .atomic)
        return CameraServerResult(data: data, files: files)
    }
    private func processing<T: Sendable>(_ work: @escaping @Sendable () throws -> T) async throws -> T {
        let task = Task.detached(priority:.userInitiated,operation:work)
        return try await withTaskCancellationHandler(operation:{try await task.value},onCancel:{task.cancel()})
    }
    func checkUpdate() async {
        guard !checkingUpdate else { return }
        checkingUpdate = true; updateStatus = "Checking releases"
        defer { checkingUpdate = false }
        do { update = try await UpdateService.check(); updateStatus = update == nil ? "Up to date" : "Update available" }
        catch { updateStatus = error.localizedDescription }
    }
}
