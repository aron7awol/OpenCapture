import Foundation
import SwiftUI
import UIKit

struct CaptureArchive: Identifiable {
    let directory: URL
    let manifest: CaptureManifest?
    var id: String { directory.lastPathComponent }
    var exportFiles: [URL] {
        (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { !$0.lastPathComponent.hasPrefix(".") }.sorted { $0.lastPathComponent < $1.lastPathComponent }) ?? []
    }
}

@MainActor final class AppModel: ObservableObject {
    let camera = CameraService()
    @Published var capabilities: CameraCapabilities?
    @Published var cameras: [CameraChoice] = []
    @Published var cameraID = ""
    @Published var mode = "raw"
    @Published var frameCount = 4
    @Published var manualExposure = false
    @Published var exposureMS = "33.333"
    @Published var iso = "100"
    @Published var manualFocus = false
    @Published var focus: Float = 0.5
    @Published var manualWB = false
    @Published var wbRed = "1.0"
    @Published var wbGreen = "1.0"
    @Published var wbBlue = "1.0"
    @Published var host = UserDefaults.standard.string(forKey: "controllerURL") ?? ""
    @Published var token = TokenStore.load()
    @Published var status = "Starting camera"
    @Published var errorMessage: String?
    @Published var busy = false
    @Published var connected = false
    @Published var configuring = false
    @Published var progress = 0.0
    @Published var archives: [CaptureArchive] = []
    @Published var update: AppUpdate?
    @Published var updateStatus = ""
    @Published var checkingUpdate = false
    private var captureTask: Task<Void, Never>?
    private var remoteTask: Task<Void, Never>?
    private var transport: CaptureTransport?

    var captureRoot: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Captures", isDirectory: true)
    }

    func startCamera() async {
        guard !busy, !configuring else { return }
        configuring = true
        capabilities = nil
        defer { configuring = false }
        do {
            capabilities = try await camera.configure(cameraID: cameraID.isEmpty ? nil : cameraID)
            cameras = CameraService.cameras
            cameraID = capabilities?.cameraID ?? ""
            status = "Ready"
            refreshArchives()
        } catch { report(error) }
    }

    func request() throws -> CaptureRequest {
        var request = CaptureRequest()
        request.format = mode == "raw" ? .bayerRAW : mode == "proraw" ? .appleProRAW : .jpeg
        request.quality = mode == "quality" || mode == "proraw" ? .quality : .speed
        request.frameCount = frameCount
        if manualExposure {
            guard let seconds = Double(exposureMS), let value = Float(iso) else { throw CaptureError.invalid("Enter numeric shutter time and ISO") }
            request.exposureSeconds = seconds / 1000
            request.iso = value
        }
        if manualFocus { request.focusPosition = focus }
        if manualWB {
            guard let r = Float(wbRed), let g = Float(wbGreen), let b = Float(wbBlue) else { throw CaptureError.invalid("Enter numeric white balance gains") }
            request.whiteBalance = WhiteBalance(red: r, green: g, blue: b)
        }
        try request.validate()
        return request
    }

    func useCurrentControls() async {
        do {
            let current = try await camera.reading()
            exposureMS = String(format: "%.5f", current.exposure * 1000)
            iso = String(format: "%.2f", current.iso)
            focus = current.focus
            wbRed = String(format: "%.4f", current.whiteBalance.red)
            wbGreen = String(format: "%.4f", current.whiteBalance.green)
            wbBlue = String(format: "%.4f", current.whiteBalance.blue)
            manualExposure = capabilities?.manualExposure == true
            manualFocus = capabilities?.manualFocus == true
            manualWB = capabilities?.manualWhiteBalance == true
        } catch { report(error) }
    }

    func previewSettings() {
        guard !busy, !connected else { return }
        do {
            let settings = try request()
            busy = true
            captureTask = Task {
                defer { busy = false; captureTask = nil }
                do { try await camera.prepare(settings); status = "Controls locked" }
                catch { report(error) }
            }
        } catch { report(error) }
    }

    func captureLocal() {
        guard !busy, !connected, capabilities != nil else { return }
        do {
            let settings = try request()
            busy = true
            captureTask = Task {
                defer { busy = false; captureTask = nil }
                do { _ = try await capture(settings); status = "Capture saved" }
                catch { report(error) }
            }
        } catch { report(error) }
    }

    private func capture(_ request: CaptureRequest) async throws -> CaptureArchive {
        guard let capabilities else { throw CaptureError.unavailable("Camera is not ready") }
        try request.validate()
        let directory = captureRoot.appendingPathComponent(request.id, isDirectory: true)
        guard !FileManager.default.fileExists(atPath: directory.path) else {
            throw CaptureError.invalid("Capture ID already exists locally; refusing to overwrite originals")
        }
        let available = try captureRoot.deletingLastPathComponent().resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
        let reservePerFrame: Int64 = request.format == .appleProRAW ? 160_000_000 : 100_000_000
        guard available > Int64(request.frameCount) * reservePerFrame + 200_000_000 else {
            throw CaptureError.unavailable("Not enough free storage for this burst")
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(request).write(to: directory.appendingPathComponent("request.json"), options: .atomic)
        let started = ISO8601DateFormatter().string(from: Date())
        progress = 0
        status = "Settling and locking controls"
        camera.pausePreview(true)
        UIApplication.shared.isIdleTimerDisabled = true
        defer { camera.pausePreview(false); UIApplication.shared.isIdleTimerDisabled = connected; refreshArchives() }
        do {
            try await camera.prepare(request)
            var frames: [FrameRecord] = []
            for index in 1...request.frameCount {
                try Task.checkCancellation()
                status = "Frame \(index) of \(request.frameCount)"
                let frame = try await camera.photo(request: request, index: index, directory: directory)
                frames.append(frame)
                progress = Double(index) / Double(request.frameCount)
            }
            let manifest = CaptureManifest(request: request, camera: capabilities, frames: frames,
                startedAt: started, completedAt: ISO8601DateFormatter().string(from: Date()),
                processing: request.format == .bayerRAW ? "AVFoundation Bayer RAW pixel format; original DNG bytes unchanged" :
                    request.format == .appleProRAW ? "AVFoundation Apple ProRAW, maximum advertised dimensions requested; demosaiced RGB, computational processing and possible multi-frame fusion; original lossless DNG unchanged; not a Bayer measurement" :
                    "AVFoundation JPEG, \(request.quality.rawValue) priority; processed, not calibrated or guaranteed unsharpened")
            try encoder.encode(manifest).write(to: directory.appendingPathComponent("manifest.json"), options: .atomic)
            return CaptureArchive(directory: directory, manifest: manifest)
        } catch {
            let failure = ["status": Task.isCancelled ? "cancelled" : "failed", "message": error.localizedDescription]
            try? encoder.encode(failure).write(to: directory.appendingPathComponent("failure.json"), options: .atomic)
            throw error
        }
    }

    func connect() {
        guard !connected, !busy, !configuring, let capabilities else { return }
        do {
            let client = try CaptureTransport(url: host, token: token)
            try TokenStore.save(token)
            host = client.base.absoluteString
            UserDefaults.standard.set(host, forKey: "controllerURL")
            transport = client
            connected = true
            UIApplication.shared.isIdleTimerDisabled = true
            remoteTask = Task {
                defer {
                    connected = false; busy = false; transport = nil; remoteTask = nil
                    UIApplication.shared.isIdleTimerDisabled = false; client.close()
                }
                do {
                    try await client.post(capabilities, to: "oc/v1/capabilities")
                    status = "Remote ready"
                    while !Task.isCancelled {
                        let lease = try await client.lease()
                        if let job = lease.request, let secret = lease.leaseToken {
                            busy = true
                            captureTask = Task { await runRemote(job, secret: secret, client: client) }
                            await captureTask?.value
                            captureTask = nil; busy = false
                        } else if let jpeg = camera.latestPreview() { try? await client.preview(jpeg) }
                        try await Task.sleep(nanoseconds: 1_000_000_000)
                    }
                } catch {
                    if !Task.isCancelled { report(error) }
                }
            }
        } catch { report(error) }
    }

    private func runRemote(_ job: CaptureRequest, secret: String, client: CaptureTransport) async {
        let heartbeat = Task {
            do {
                while !Task.isCancelled {
                    try await Task.sleep(nanoseconds: 10_000_000_000)
                    if try await client.heartbeat(id: job.id, lease: secret) { captureTask?.cancel(); return }
                }
            } catch { if !Task.isCancelled { captureTask?.cancel() } }
        }
        defer { heartbeat.cancel() }
        do {
            if job.operation == .applyControls {
                status = "Applying remote controls"
                try await camera.prepare(job)
                let reading = try await camera.reading()
                guard let capabilities else { throw CaptureError.unavailable("Camera disconnected") }
                try Task.checkCancellation()
                let result = CameraControlResult(request: job, camera: capabilities,
                    deviceExposureSeconds: reading.exposure, deviceISO: reading.iso,
                    lensPosition: reading.focus, whiteBalance: reading.whiteBalance,
                    completedAt: ISO8601DateFormatter().string(from: Date()))
                try await client.post(result, to: "oc/v1/jobs/\(job.id)/controls-complete", lease: secret)
                status = "Remote controls locked"
                return
            }
            let archive = try await capture(job)
            guard let manifest = archive.manifest else { throw CaptureError.unavailable("Capture manifest is missing") }
            status = "Uploading originals"
            for frame in manifest.frames {
                for file in [frame.file, frame.metadataFile] + (frame.previewFile.map { [$0] } ?? []) {
                    try Task.checkCancellation()
                    try await client.upload(file, from: archive.directory, id: job.id, lease: secret)
                }
            }
            try Task.checkCancellation()
            try await client.post(manifest, to: "oc/v1/jobs/\(job.id)/complete", lease: secret)
            status = "Uploaded \(manifest.frames.count) frames"
        } catch {
            await client.finish(id: job.id, lease: secret, state: Task.isCancelled ? "cancelled" : "failed", message: error.localizedDescription)
            if !Task.isCancelled { report(error) } else { status = "Capture cancelled; local files retained" }
        }
    }

    func cancel() { captureTask?.cancel(); status = "Cancelling after current frame" }
    func disconnect() { captureTask?.cancel(); remoteTask?.cancel() }
    func background() { disconnect(); cancel(); camera.stop() }

    func refreshArchives() {
        let directories = (try? FileManager.default.contentsOfDirectory(at: captureRoot, includingPropertiesForKeys: [.isDirectoryKey, .creationDateKey])) ?? []
        archives = directories.filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }.map {
            let data = try? Data(contentsOf: $0.appendingPathComponent("manifest.json"))
            return CaptureArchive(directory: $0, manifest: data.flatMap { try? JSONDecoder().decode(CaptureManifest.self, from: $0) })
        }.sorted { ($0.manifest?.startedAt ?? $0.id) > ($1.manifest?.startedAt ?? $1.id) }
    }

    func checkUpdate() async {
        guard !checkingUpdate, !busy else { return }
        checkingUpdate = true; updateStatus = "Checking releases"
        defer { checkingUpdate = false }
        do { update = try await UpdateService.check(); updateStatus = update == nil ? "Up to date" : "Update available" }
        catch { updateStatus = error.localizedDescription }
    }

    private func report(_ error: Error) {
        status = Task.isCancelled ? "Cancelled" : "Error"
        if !Task.isCancelled { errorMessage = error.localizedDescription }
    }
}
