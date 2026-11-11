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
    @Published var busy = false { didSet { updateScreenMode() } }
    @Published var connected = false
    @Published private(set) var remoteRunning = false
    @Published private(set) var remoteReadyEnabled = UserDefaults.standard.bool(forKey: "remoteReady")
    @Published private(set) var remoteDimmed = UserDefaults.standard.bool(forKey: "remoteReady")
    @Published var configuring = false
    @Published var progress = 0.0
    @Published var archives: [CaptureArchive] = []
    @Published var autoDeleteRemote = (UserDefaults.standard.object(forKey: "autoDeleteRemote") as? Bool) ?? true {
        didSet { UserDefaults.standard.set(autoDeleteRemote, forKey: "autoDeleteRemote") }
    }
    @Published private(set) var deletingArchives = false
    @Published var update: AppUpdate?
    @Published var updateStatus = ""
    @Published var checkingUpdate = false
    private var captureTask: Task<Void, Never>?
    private var remoteTask: Task<Void, Never>?
    private var transport: CaptureTransport?
    private var isForeground = false
    private var screenActive = false
    private var previousBrightness: CGFloat?
    private var appliedBrightness: CGFloat?
    var remoteLocked: Bool { remoteRunning || remoteReadyEnabled }
    var canDeleteArchives: Bool { !busy && !remoteLocked && !deletingArchives }

    func foreground() async {
        isForeground = true; screenActive = true; updateScreenMode()
        guard !busy, !configuring, !remoteRunning else { return }
        if capabilities == nil { await startCamera() }
        else if remoteReadyEnabled { connect() }
    }

    func inactive() { screenActive = false; updateScreenMode() }

    var captureRoot: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Captures", isDirectory: true)
    }

    func startCamera() async {
        guard isForeground, !busy, !configuring, !remoteRunning, !deletingArchives else { return }
        configuring = true
        capabilities = nil
        defer {
            configuring = false
            if isForeground, remoteReadyEnabled, capabilities != nil { connect() }
        }
        do {
            capabilities = try await camera.configure(cameraID: cameraID.isEmpty ? nil : cameraID)
            guard isForeground else { camera.stop(); capabilities = nil; return }
            cameras = CameraService.cameras
            cameraID = capabilities?.cameraID ?? ""
            status = "Ready"
            refreshArchives()
        } catch { if isForeground { report(error) } }
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
        guard !busy, !remoteLocked, !deletingArchives else { return }
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
        guard !busy, !remoteLocked, !deletingArchives else { return }
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
        guard !busy, !remoteLocked, !deletingArchives, capabilities != nil else { return }
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
        let available = try captureRoot.deletingLastPathComponent().resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
        let reservePerFrame: Int64 = request.format == .appleProRAW ? 160_000_000 : 100_000_000
        guard available > Int64(request.frameCount) * reservePerFrame + 200_000_000 else {
            throw CaptureError.unavailable("Not enough free storage for this burst")
        }
        let directory = try CaptureArchiveStore(root: captureRoot).create(request.id)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(request).write(to: directory.appendingPathComponent("request.json"), options: .atomic)
        let started = ISO8601DateFormatter().string(from: Date())
        progress = 0
        status = "Settling and locking controls"
        camera.pausePreview(true)
        updateScreenMode()
        defer { camera.pausePreview(false); updateScreenMode(); refreshArchives() }
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
        guard isForeground, !remoteRunning, !busy, !configuring, !deletingArchives, let capabilities else { return }
        do {
            let origin = try EndpointPolicy.controllerURL(host)
            try EndpointPolicy.validatePairingToken(token, for: origin)
            try TokenStore.save(token)
            host = origin.absoluteString
            UserDefaults.standard.set(host, forKey: "controllerURL")
            let savedHost = host, savedToken = token
            remoteRunning = true; updateScreenMode()
            remoteTask = Task {
                defer {
                    connected = false; busy = false; transport = nil; remoteTask = nil; remoteRunning = false
                    updateScreenMode()
                    // A foreground return may have happened while the cancelled job was
                    // draining. Reopen only after that task has relinquished the camera.
                    if isForeground, remoteReadyEnabled { Task { await foreground() } }
                }
                do {
                    var failures = 0
                    while !Task.isCancelled {
                        let client = try CaptureTransport(url: savedHost, token: savedToken)
                        transport = client
                        do {
                            status = "Connecting to the remembered PC…"
                            try await client.post(capabilities, to: "oc/v1/capabilities")
                            try Task.checkCancellation()
                            connected = true; failures = 0; status = "Connected. Ready for Windows controls."
                            while !Task.isCancelled {
                                let lease = try await client.lease()
                                try Task.checkCancellation()
                                if let job = lease.request, let secret = lease.leaseToken {
                                    busy = true
                                    captureTask = Task { await runRemote(job, secret: secret, client: client) }
                                    await captureTask?.value
                                    captureTask = nil; busy = false
                                } else if let jpeg = camera.latestPreview() { try? await client.preview(jpeg) }
                                try await Task.sleep(nanoseconds: 1_000_000_000)
                            }
                            client.close(); break
                        } catch {
                            client.close(); transport = nil; connected = false
                            if Task.isCancelled || !isForeground { break }
                            guard remoteReadyEnabled, RemoteReconnect.retryable(error) else { throw error }
                            let delay = RemoteReconnect.delaySeconds(failure: failures)
                            failures += 1
                            status = "PC unavailable. Reconnecting in \(delay) seconds…"
                            try await Task.sleep(nanoseconds: UInt64(delay) * 1_000_000_000)
                        }
                    }
                } catch {
                    if !Task.isCancelled {
                        remoteReadyEnabled = false; remoteDimmed = false
                        UserDefaults.standard.set(false, forKey: "remoteReady")
                        report(error)
                    }
                }
            }
        } catch { report(error) }
    }

    func enterRemoteReady() {
        guard isForeground, !configuring, !deletingArchives, !busy || remoteRunning else { return }
        do {
            let origin = try EndpointPolicy.controllerURL(host)
            try EndpointPolicy.validatePairingToken(token, for: origin)
            try TokenStore.save(token)
            host = origin.absoluteString
            UserDefaults.standard.set(host, forKey: "controllerURL")
            UserDefaults.standard.set(true, forKey: "remoteReady")
            remoteReadyEnabled = true; remoteDimmed = true
            if capabilities == nil { status = "Waiting for camera. Show controls to check camera access." }
            updateScreenMode(); connect()
        } catch { report(error) }
    }

    func showControls() { remoteDimmed = false; updateScreenMode() }
    func dimRemoteScreen() { guard remoteReadyEnabled else { return }; remoteDimmed = true; updateScreenMode() }

    private func updateScreenMode() {
        UIApplication.shared.isIdleTimerDisabled = screenActive && (remoteReadyEnabled || remoteRunning || busy)
        let dim = screenActive && remoteReadyEnabled && remoteDimmed
        if dim, previousBrightness == nil {
            previousBrightness = UIScreen.main.brightness
            appliedBrightness = min(UIScreen.main.brightness, 0.08)
            UIScreen.main.brightness = appliedBrightness!
        } else if !dim, let previousBrightness {
            // Respect a manual brightness adjustment made while the dim view was open.
            if let appliedBrightness, abs(UIScreen.main.brightness - appliedBrightness) < 0.001 { UIScreen.main.brightness = previousBrightness }
            self.previousBrightness = nil; appliedBrightness = nil
        }
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
            let confirmation = try await client.post(manifest, to: "oc/v1/jobs/\(job.id)/complete", lease: secret)
            try CaptureArchiveStore.confirmUpload(confirmation, id: job.id)
            do {
                try CaptureArchiveStore(root: captureRoot).completeUpload(job.id, confirmation: confirmation, deleteAfter: autoDeleteRemote)
                if autoDeleteRemote {
                    status = "Uploaded \(manifest.frames.count) frames. Phone copy deleted."
                } else { status = "Uploaded \(manifest.frames.count) frames; phone copy retained." }
            } catch { status = "Upload confirmed. Phone cleanup failed; check Captures." }
            refreshArchives()
        } catch {
            await client.finish(id: job.id, lease: secret, state: Task.isCancelled ? "cancelled" : "failed", message: error.localizedDescription)
            if !Task.isCancelled {
                if remoteReadyEnabled { status = "Capture failed: \(error.localizedDescription). Originals retained." }
                else { report(error) }
            } else { status = "Capture cancelled; local files retained" }
        }
    }

    func cancel() { captureTask?.cancel(); status = "Cancelling after current frame" }
    func disconnect() {
        remoteReadyEnabled = false; remoteDimmed = false
        UserDefaults.standard.set(false, forKey: "remoteReady")
        captureTask?.cancel(); remoteTask?.cancel(); transport?.close()
        status = "Disconnecting…"; updateScreenMode()
    }
    func background() {
        isForeground = false; screenActive = false
        captureTask?.cancel(); remoteTask?.cancel(); transport?.close()
        camera.stop(); capabilities = nil; updateScreenMode()
        status = "Paused. Return to OpenCapture to resume Remote ready."
    }

    func refreshArchives() {
        let directories = (try? FileManager.default.contentsOfDirectory(at: captureRoot, includingPropertiesForKeys: [.isDirectoryKey, .creationDateKey])) ?? []
        archives = directories.filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }.map {
            let data = try? Data(contentsOf: $0.appendingPathComponent("manifest.json"))
            return CaptureArchive(directory: $0, manifest: data.flatMap { try? JSONDecoder().decode(CaptureManifest.self, from: $0) })
        }.sorted { ($0.manifest?.startedAt ?? $0.id) > ($1.manifest?.startedAt ?? $1.id) }
    }

    @discardableResult func deleteArchives(_ ids: [String]) async -> Bool {
        guard canDeleteArchives else { errorMessage = "End Remote ready and disconnect before deleting captures."; return false }
        deletingArchives = true
        defer { deletingArchives = false; refreshArchives() }
        let root = captureRoot
        do {
            try await Task.detached(priority: .userInitiated) {
                let store = CaptureArchiveStore(root: root)
                for id in ids { try store.delete(id) }
            }.value
            status = "Deleted \(ids.count) captures from this phone"
            return true
        } catch { report(error); return false }
    }

    func checkUpdate() async {
        guard !checkingUpdate, !busy else { return }
        checkingUpdate = true; updateStatus = "Checking releases"
        defer { checkingUpdate = false }
        do { update = try await UpdateService.check(); updateStatus = update == nil ? "Up to date" : "Update available" }
        catch { updateStatus = error.localizedDescription }
    }

    private func report(_ error: Error) {
        showControls()
        status = Task.isCancelled ? "Cancelled" : "Error"
        if !Task.isCancelled { errorMessage = error.localizedDescription }
    }
}
