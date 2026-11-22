import AVFoundation
import CoreImage
import CryptoKit
import ImageIO
import UIKit

struct CameraChoice: Identifiable, Codable, Sendable {
    let id: String
    let name: String
}

struct DeviceReading {
    var exposure: Double
    var iso: Float
    var focus: Float
    var whiteBalance: WhiteBalance
}

// Session state is queue-confined; the preview mailbox is protected by previewLock.
final class CameraService: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    let diagnostics = CameraDiagnostics()
    private var sessionObservers: [NSObjectProtocol] = []
    private let queue = DispatchQueue(label: "OpenCapture.camera")
    private let previewQueue = DispatchQueue(label: "OpenCapture.preview", qos: .utility)
    private let output = AVCapturePhotoOutput()
    private let video = AVCaptureVideoDataOutput()
    private let imageContext = CIContext(options: [.cacheIntermediates: false])
    private let photoContext = CIContext(options: [.cacheIntermediates: false])
    private var preparedKey: CaptureControlKey?
    private var preparedActualKey: CaptureControlKey?
    private var preparationTiming: [String:UInt64] = [:]
    private var device: AVCaptureDevice?
    private var delegates: [Int64: StillDelegate] = [:]
    private var bracketDelegate: BracketDelegate?
    private var experiment: CaptureExperiment?
    private var experimentEvidence: [String:String] = [:]
    private var experimentRestore: (proRAW: Bool, dimensions: CMVideoDimensions,
        quality: AVCapturePhotoOutput.QualityPrioritization, video: Bool)?
    private var preparationToken: UUID?
    private var resourcesPrepared: Bool?
    @MainActor private weak var aimingLayer: AVCaptureVideoPreviewLayer?
    @MainActor private var aimingEnabled = true
    private var previewTime = 0.0
    private let previewLock = NSLock()
    private var previewData: Data?
    private var previewPaused = false
    private var standardDimensions = CMVideoDimensions(width: 0, height: 0)
    private var proRAWDimensions: CMVideoDimensions?

    override init() {
        super.init()
        let notifications: [(Notification.Name,String)] = [
            (AVCaptureSession.runtimeErrorNotification,"session_runtime_error"),
            (AVCaptureSession.wasInterruptedNotification,"session_interrupted"),
            (AVCaptureSession.interruptionEndedNotification,"session_interruption_ended"),
            (AVCaptureSession.didStartRunningNotification,"session_started"),
            (AVCaptureSession.didStopRunningNotification,"session_stopped")]
        sessionObservers=notifications.map { name,kind in
            NotificationCenter.default.addObserver(forName:name,object:session,queue:nil) { [weak self] note in
                guard let self else { return }
                var fields=["running":String(self.session.isRunning),"interrupted":String(self.session.isInterrupted)]
                if let error=note.userInfo?[AVCaptureSessionErrorKey] as? NSError {
                    fields.merge(CameraDiagnostics.errorFields(error)) { _,new in new }
                }
                if let reason=note.userInfo?[AVCaptureSessionInterruptionReasonKey] as? NSNumber {
                    fields["interruptionReason"]=reason.stringValue
                }
                self.diagnostics.record(kind,fields,fault:kind=="session_runtime_error" || kind=="session_interrupted")
            }
        }
    }
    deinit { for observer in sessionObservers { NotificationCenter.default.removeObserver(observer) } }

    // Called only on the camera queue. Diagnostic reads do not mutate camera controls.
    private func traceState(_ kind: String, requestID: String? = nil, extra: [String:String] = [:]) {
        var fields=["running":String(session.isRunning),"interrupted":String(session.isInterrupted),
            "unfinishedPhotos":String(delegates.count + (bracketDelegate?.count ?? 0)),"readiness":String(output.captureReadiness.rawValue)]
        if let d=device {
            let actual=Self.read(d)
            fields.merge(["exposureSeconds":String(actual.exposure),"iso":String(actual.iso),
                "focusPosition":String(actual.focus),"wbRed":String(actual.whiteBalance.red),
                "wbGreen":String(actual.whiteBalance.green),"wbBlue":String(actual.whiteBalance.blue),
                "exposureMode":String(d.exposureMode.rawValue),"focusMode":String(d.focusMode.rawValue),
                "whiteBalanceMode":String(d.whiteBalanceMode.rawValue),"adjustingExposure":String(d.isAdjustingExposure),
                "adjustingFocus":String(d.isAdjustingFocus),"adjustingWhiteBalance":String(d.isAdjustingWhiteBalance)]) { _,new in new }
        }
        fields.merge(extra) { _,new in new }
        diagnostics.record(kind,fields,requestID:requestID)
    }

    static var cameras: [CameraChoice] {
        AVCaptureDevice.DiscoverySession(deviceTypes: [.builtInWideAngleCamera, .builtInUltraWideCamera, .builtInTelephotoCamera], mediaType: .video, position: .back).devices.map {
            CameraChoice(id: $0.uniqueID, name: $0.localizedName)
        }
    }

    private func onQueue<T>(_ block: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do { continuation.resume(returning: try block()) }
                catch { continuation.resume(throwing: error) }
            }
        }
    }

    func configure(cameraID: String?) async throws -> CameraCapabilities {
        let granted = await AVCaptureDevice.requestAccess(for: .video)
        guard granted else { throw CaptureError.unavailable("Camera permission is disabled. Enable it in Settings.") }
        return try await onQueue { [self] in
            guard delegates.isEmpty, bracketDelegate == nil, experimentRestore == nil else { throw CaptureError.unavailable("A capture is still in flight") }
            preparedKey=nil; preparedActualKey=nil; preparationTiming=[:]
            diagnostics.record("session_stop_requested",["reason":"reconfigure"])
            session.stopRunning()
            session.beginConfiguration()
            do {
                for input in session.inputs { session.removeInput(input) }
                for item in session.outputs { session.removeOutput(item) }
                session.sessionPreset = .photo
                let devices = AVCaptureDevice.DiscoverySession(deviceTypes: [.builtInWideAngleCamera, .builtInUltraWideCamera, .builtInTelephotoCamera], mediaType: .video, position: .back).devices
                if let cameraID, !devices.contains(where: { $0.uniqueID == cameraID }) {
                    throw CaptureError.unavailable("The requested camera is not available")
                }
                guard let chosen = devices.first(where: { $0.uniqueID == cameraID }) ?? devices.first(where: { $0.deviceType == .builtInWideAngleCamera }) ?? devices.first else {
                    throw CaptureError.unavailable("No rear camera is available")
                }
                let input = try AVCaptureDeviceInput(device: chosen)
                guard session.canAddInput(input), session.canAddOutput(output) else { throw CaptureError.unavailable("Camera session configuration failed") }
                session.addInput(input)
                session.addOutput(output)
                device = chosen
                output.maxPhotoQualityPrioritization = .quality
                // Enable before starting the session; selecting a Bayer pixel format still
                // requests Bayer RAW. ProRAW is a separate, explicit format, never a fallback.
                output.isAppleProRAWEnabled = output.isAppleProRAWSupported
                if output.isContentAwareDistortionCorrectionSupported { output.isContentAwareDistortionCorrectionEnabled = false }
                // Keep standard Bayer/JPEG captures at the existing size. ProRAW requests
                // the largest dimensions advertised by the selected camera configuration.
                let sizes = chosen.activeFormat.supportedMaxPhotoDimensions.sorted { Int64($0.width) * Int64($0.height) < Int64($1.width) * Int64($1.height) }
                if let size = sizes.last(where: { Int64($0.width) * Int64($0.height) <= 13_000_000 }) ?? sizes.first {
                    standardDimensions = size
                    proRAWDimensions = output.isAppleProRAWEnabled ? sizes.last : nil
                    output.maxPhotoDimensions = proRAWDimensions ?? size
                }
                video.alwaysDiscardsLateVideoFrames = true
                video.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
                video.setSampleBufferDelegate(self, queue: previewQueue)
                if session.canAddOutput(video) { session.addOutput(video) }
                for connection in [output.connection(with: .video), video.connection(with: .video)].compactMap({ $0 }) {
                    if connection.isVideoRotationAngleSupported(0) { connection.videoRotationAngle = 0 }
                    if connection.isVideoStabilizationSupported { connection.preferredVideoStabilizationMode = .off }
                }
                session.commitConfiguration()
                diagnostics.record("session_start_requested",["reason":"configured"])
                session.startRunning()
                traceState("configuration_completed")
                return capabilities(chosen)
            } catch {
                session.commitConfiguration()
                throw error
            }
        }
    }

    private func capabilities(_ device: AVCaptureDevice) -> CameraCapabilities {
        let raw = output.availableRawPhotoPixelFormatTypes.contains(where: AVCapturePhotoOutput.isBayerRAWPixelFormat)
        let proRAW = output.isAppleProRAWEnabled && output.availableRawPhotoPixelFormatTypes.contains(where: AVCapturePhotoOutput.isAppleProRAWPixelFormat)
        let deviceID = UserDefaults.standard.string(forKey: "captureDeviceID") ?? UUID().uuidString.lowercased()
        UserDefaults.standard.set(deviceID, forKey: "captureDeviceID")
        return CameraCapabilities(cameraID: device.uniqueID, name: device.localizedName,
            formats: (raw ? [.bayerRAW] : []) + (proRAW ? [.appleProRAW] : []) + [.jpeg], minISO: device.activeFormat.minISO,
            maxISO: device.activeFormat.maxISO, minExposureSeconds: device.activeFormat.minExposureDuration.seconds,
            maxExposureSeconds: device.activeFormat.maxExposureDuration.seconds,
            manualExposure: device.isExposureModeSupported(.custom),
            manualFocus: device.isLockingFocusWithCustomLensPositionSupported,
            manualWhiteBalance: device.isLockingWhiteBalanceWithCustomDeviceGainsSupported,
            maxWhiteBalanceGain: device.maxWhiteBalanceGain,
            maxPhotoWidth: Int(standardDimensions.width), maxPhotoHeight: Int(standardDimensions.height),
            osVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown",
            appBuild: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String,
            deviceID: deviceID, operations: [.capture, .applyControls],
            proRAWMaxPhotoWidth: proRAW ? proRAWDimensions.map { Int($0.width) } : nil,
            proRAWMaxPhotoHeight: proRAW ? proRAWDimensions.map { Int($0.height) } : nil,
            platform:"ios", frameAveragingModes: raw && Int64(standardDimensions.width)*Int64(standardDimensions.height) <= 13_000_000 &&
                output.availableRawPhotoPixelFormatTypes.contains(where:{RawCaptureDiagnostics.formats[$0] != nil}) ? [BayerAverage.mode] : [],
            captureStreamingModes:["originals-v1"], optionalPhotoPreview:true,
            captureExperiments: raw ? CaptureExperiment.allCases.filter {
                $0 != .bracketPair || output.maxBracketedCapturePhotoCount >= 2
            } : [])
    }

    @MainActor func registerAimingLayer(_ layer: AVCaptureVideoPreviewLayer) {
        aimingLayer = layer
        layer.connection?.isEnabled = aimingEnabled
    }
    @MainActor private func enableAiming(_ enabled: Bool) {
        aimingEnabled = enabled
        aimingLayer?.connection?.isEnabled = enabled
    }

    /// Every experiment restores its output configuration before cameraReady.
    /// No default, persisted preference, or public update feed changes here.
    func beginExperiment(_ request: CaptureRequest) async throws {
        try request.validate()
        guard let mode = request.captureExperiment else { return }
        let started = RawCaptureDiagnostics.now
        try await onQueue { [self] in
            guard delegates.isEmpty, bracketDelegate == nil, experimentRestore == nil,
                  let device, session.isRunning,
                  capabilities(device).captureExperiments?.contains(mode) == true else {
                throw CaptureError.unavailable("Capture experiment unavailable or camera busy")
            }
            experimentRestore = (output.isAppleProRAWEnabled, output.maxPhotoDimensions,
                                 output.maxPhotoQualityPrioritization, session.outputs.contains(video))
            experiment = mode
            experimentEvidence = ["mode":mode.rawValue,"policy":"ios-capture-experiment-v1"]
            preparedKey = nil; preparedActualKey = nil
        }
        if mode == .noPreview { await enableAiming(false) }
        try await onQueue { [self] in
            guard session.isRunning else { throw CaptureError.unavailable("Camera stopped before experiment configuration") }
            if mode == .noPreview || mode == .bayerOnly {
                session.beginConfiguration()
                if mode == .noPreview { session.removeOutput(video) }
                if mode == .bayerOnly {
                    output.isAppleProRAWEnabled = false
                    output.maxPhotoDimensions = standardDimensions
                    output.maxPhotoQualityPrioritization = .speed
                }
                session.commitConfiguration()
            }
            experimentEvidence["configurationElapsedNs"] = String(RawCaptureDiagnostics.now - started)
            experimentEvidence["videoOutputAttached"] = String(session.outputs.contains(video))
            experimentEvidence["aimingPreviewEnabled"] = String(mode != .noPreview)
            experimentEvidence["proRAWEnabled"] = String(output.isAppleProRAWEnabled)
            experimentEvidence["outputMaxWidth"] = String(output.maxPhotoDimensions.width)
            experimentEvidence["outputMaxHeight"] = String(output.maxPhotoDimensions.height)
            experimentEvidence["maxQualityPriority"] = String(output.maxPhotoQualityPrioritization.rawValue)
            diagnostics.record("experiment_configured", experimentEvidence, requestID:request.id)
        }
    }

    func prepareExperimentResources(_ request: CaptureRequest) async throws {
        guard request.captureExperiment == .preparedRAW else { return }
        let started = RawCaptureDiagnostics.now
        let token = UUID()
        try await onQueue { [self] in
            guard session.isRunning, experiment == .preparedRAW else { throw CaptureError.unavailable("Camera stopped before resource preparation") }
            let settings = try photoSettings(request)
            preparationToken = token; resourcesPrepared = nil
            output.setPreparedPhotoSettingsArray([settings]) { [weak self] prepared, error in
                guard let self else { return }
                self.queue.async {
                    guard self.preparationToken == token else { return }
                    self.resourcesPrepared = prepared && error == nil
                    if let error { self.diagnostics.record("resource_preparation_error",CameraDiagnostics.errorFields(error),requestID:request.id,fault:true) }
                }
            }
        }
        while true {
            try Task.checkCancellation()
            let ready = try await onQueue { [self] () -> Bool? in
                guard session.isRunning, preparationToken == token else { throw CaptureError.unavailable("Resource preparation interrupted") }
                return resourcesPrepared
            }
            if let ready {
                guard ready else { throw CaptureError.unavailable("RAW resources could not be prepared") }
                break
            }
            guard RawCaptureDiagnostics.now - started < 60_000_000_000 else { throw CaptureError.unavailable("RAW resource preparation timed out") }
            try await Task.sleep(nanoseconds:20_000_000)
        }
        try await onQueue { [self] in
            experimentEvidence["resourcePreparationElapsedNs"] = String(RawCaptureDiagnostics.now - started)
            experimentEvidence["resourcesPrepared"] = "true"
            diagnostics.record("experiment_resources_prepared",experimentEvidence,requestID:request.id)
        }
    }

    func endExperiment(_ request: CaptureRequest) async throws {
        guard request.captureExperiment != nil else { return }
        let started = RawCaptureDiagnostics.now
        do {
            try await onQueue { [self] in
                guard let saved = experimentRestore else { return }
                guard delegates.isEmpty, bracketDelegate == nil else { throw CaptureError.unavailable("Experiment writers have not drained") }
                preparationToken = nil; resourcesPrepared = nil
                if experiment == .preparedRAW { output.setPreparedPhotoSettingsArray([], completionHandler:nil) }
                session.beginConfiguration()
                output.isAppleProRAWEnabled = saved.proRAW
                output.maxPhotoDimensions = saved.dimensions
                output.maxPhotoQualityPrioritization = saved.quality
                if saved.video && !session.outputs.contains(video) {
                    guard session.canAddOutput(video) else {
                        session.commitConfiguration()
                        throw CaptureError.unavailable("Could not restore preview output; reconnect camera")
                    }
                    session.addOutput(video)
                    if let connection = video.connection(with:.video) {
                        if connection.isVideoRotationAngleSupported(0) { connection.videoRotationAngle = 0 }
                        if connection.isVideoStabilizationSupported { connection.preferredVideoStabilizationMode = .off }
                    }
                }
                session.commitConfiguration()
                preparedKey = nil; preparedActualKey = nil
                diagnostics.record("experiment_restored",["mode":experiment?.rawValue ?? "unknown",
                    "restorationElapsedNs":String(RawCaptureDiagnostics.now-started),
                    "running":String(session.isRunning)],requestID:request.id)
                experimentRestore = nil; experiment = nil; experimentEvidence = [:]
            }
            await enableAiming(true)
        } catch {
            diagnostics.record("experiment_restore_failed",CameraDiagnostics.errorFields(error),requestID:request.id,fault:true)
            try? await onQueue { [self] in
                guard delegates.isEmpty, bracketDelegate == nil else { return }
                session.stopRunning()
                preparedKey = nil; preparedActualKey = nil; preparationToken = nil
                experimentRestore = nil; experiment = nil; experimentEvidence = [:]
            }
            await enableAiming(true)
            throw error
        }
    }

    // Queue-confined. A new settings identity is required for every submission.
    private func photoSettings(_ request: CaptureRequest, bracketCount: Int? = nil) throws -> AVCapturePhotoSettings {
        let settings: AVCapturePhotoSettings
        if request.format != .jpeg {
            let query: (OSType) -> Bool = request.format == .appleProRAW
                ? AVCapturePhotoOutput.isAppleProRAWPixelFormat : AVCapturePhotoOutput.isBayerRAWPixelFormat
            guard let raw = output.availableRawPhotoPixelFormatTypes.first(where: {
                query($0) && (request.averaging == nil || RawCaptureDiagnostics.formats[$0] != nil)
            }) else { throw CaptureError.unavailable("Requested RAW format is unavailable") }
            if let count = bracketCount {
                guard (1...2).contains(count), count <= output.maxBracketedCapturePhotoCount,
                      request.captureExperiment == .bracketPair, let seconds = request.exposureSeconds,
                      let iso = request.iso else { throw CaptureError.invalid("Unsupported RAW bracket") }
                let brackets = (0..<count).map { _ in AVCaptureManualExposureBracketedStillImageSettings.manualExposureSettings(
                    exposureDuration:CMTime(seconds:seconds,preferredTimescale:1_000_000_000), iso:iso) }
                let bracket = AVCapturePhotoBracketSettings(rawPixelFormatType:raw, processedFormat:nil, bracketedSettings:brackets)
                bracket.isLensStabilizationEnabled = false
                settings = bracket
            } else { settings = AVCapturePhotoSettings(rawPixelFormatType:raw) }
        } else { settings = AVCapturePhotoSettings(format:[AVVideoCodecKey:AVVideoCodecType.jpeg]) }
        settings.maxPhotoDimensions = request.format == .appleProRAW ? (proRAWDimensions ?? standardDimensions) : standardDimensions
        settings.photoQualityPrioritization = request.quality == .speed ? .speed : .quality
        if output.supportedFlashModes.contains(.off) { settings.flashMode = .off }
        if request.includePreview != false, let previewType = settings.availablePreviewPhotoPixelFormatTypes.first {
            settings.previewPhotoFormat = [kCVPixelBufferPixelFormatTypeKey as String:previewType,
                kCVPixelBufferWidthKey as String:640,kCVPixelBufferHeightKey as String:480]
        }
        return settings
    }

    func reading() async throws -> DeviceReading {
        try await onQueue { [self] in
            guard let d = device else { throw CaptureError.unavailable("Camera is not ready") }
            return Self.read(d)
        }
    }

    private static func read(_ d: AVCaptureDevice) -> DeviceReading {
        let gains = d.deviceWhiteBalanceGains
        return DeviceReading(exposure: d.exposureDuration.seconds, iso: d.iso, focus: d.lensPosition,
            whiteBalance: WhiteBalance(red: gains.redGain, green: gains.greenGain, blue: gains.blueGain))
    }

    func prepare(_ request: CaptureRequest) async throws {
        try request.validate()
        let started=RawCaptureDiagnostics.now
        let key=CaptureControlKey(request)
        func reusable(_ d: AVCaptureDevice, _ key: CaptureControlKey) -> Bool {
            let reading=Self.read(d)
            return !d.isAdjustingExposure && !d.isAdjustingFocus && !d.isAdjustingWhiteBalance &&
                (d.exposureMode == .custom || d.exposureMode == .locked) && d.focusMode == .locked &&
                d.whiteBalanceMode == .locked && key.honors(exposure:reading.exposure,iso:reading.iso,
                    focus:reading.focus,whiteBalance:reading.whiteBalance)
        }
        let canReuse=try await onQueue { [self] in
            traceState("preparation_started",requestID:request.id)
            guard delegates.isEmpty else { throw CaptureError.unavailable("A photo is still in flight") }
            guard let key, let d=device, session.isRunning,
                  key == preparedKey || key == preparedActualKey else {
                traceState("reuse_declined",requestID:request.id,extra:["reason":key == nil ? "partial_or_automatic_request" :
                    (device == nil ? "no_device" : (!session.isRunning ? "session_stopped" : "control_key_changed"))])
                return false
            }
            let reuse=reusable(d,key)
            traceState(reuse ? "reuse_eligible" : "reuse_declined",requestID:request.id,
                extra:["reason":reuse ? "verified_matching_key" : "actual_controls_modes_or_adjusting_flags",
                       "requestedKeyMatches":String(key==preparedKey),"observedKeyMatches":String(key==preparedActualKey)])
            return reuse
        }
        if canReuse {
            // Physical pattern settling remains independent of control reuse.
            try await Task.sleep(nanoseconds:UInt64(max(250,request.settleMilliseconds))*1_000_000)
            let valid=try await onQueue { [self] in
                guard delegates.isEmpty else { throw CaptureError.unavailable("A photo is still in flight") }
                guard let key, let d=device, session.isRunning, reusable(d,key) else { return false }
                preparationTiming=["preparationStartedElapsedNs":started,"controlsLockedElapsedNs":RawCaptureDiagnostics.now,"controlsReused":1]
                traceState("preparation_completed_reused",requestID:request.id)
                return true
            }
            if valid { return }
            diagnostics.record("reuse_invalidated_after_settle",requestID:request.id)
        }
        try await onQueue { [self] in
            guard delegates.isEmpty else { throw CaptureError.unavailable("A photo is still in flight") }
            preparedKey=nil; preparedActualKey=nil
            guard let d = device, session.isRunning else { throw CaptureError.unavailable("Camera is not running") }
            if request.format == .bayerRAW && !output.availableRawPhotoPixelFormatTypes.contains(where: AVCapturePhotoOutput.isBayerRAWPixelFormat) {
                throw CaptureError.unavailable("This camera configuration does not support Bayer RAW")
            }
            if request.format == .appleProRAW && (!output.isAppleProRAWEnabled || proRAWDimensions == nil ||
                !output.availableRawPhotoPixelFormatTypes.contains(where: AVCapturePhotoOutput.isAppleProRAWPixelFormat)) {
                throw CaptureError.unavailable("Apple ProRAW is unavailable on this camera. Select a supported Pro camera.")
            }
            try d.lockForConfiguration()
            defer { d.unlockForConfiguration() }
            if let seconds = request.exposureSeconds, let iso = request.iso {
                guard d.isExposureModeSupported(.custom), seconds >= d.activeFormat.minExposureDuration.seconds,
                      seconds <= d.activeFormat.maxExposureDuration.seconds,
                      (d.activeFormat.minISO...d.activeFormat.maxISO).contains(iso) else {
                    throw CaptureError.invalid("Exposure or ISO is outside this camera's supported range")
                }
                traceState("custom_exposure_submitted",requestID:request.id,extra:["requestedSeconds":String(seconds),"requestedISO":String(iso)])
                d.setExposureModeCustom(duration: CMTime(seconds: seconds, preferredTimescale: 1_000_000_000), iso: iso) { [weak self] syncTime in
                    self?.diagnostics.record("custom_exposure_completed",["syncTimeSeconds":String(syncTime.seconds)],requestID:request.id)
                }
            } else {
                guard d.isExposureModeSupported(.continuousAutoExposure) else { throw CaptureError.unavailable("Auto exposure is unavailable") }
                d.exposureMode = .continuousAutoExposure
                traceState("automatic_exposure_enabled",requestID:request.id)
            }
            if let focus = request.focusPosition {
                guard d.isLockingFocusWithCustomLensPositionSupported else { throw CaptureError.unavailable("Manual focus is unavailable") }
                d.setFocusModeLocked(lensPosition: focus)
            } else if d.isFocusModeSupported(.continuousAutoFocus) { d.focusMode = .continuousAutoFocus }
            if let wb = request.whiteBalance {
                guard d.isLockingWhiteBalanceWithCustomDeviceGainsSupported,
                      [wb.red, wb.green, wb.blue].allSatisfy({ (1...d.maxWhiteBalanceGain).contains($0) }) else {
                    throw CaptureError.invalid("White balance gains are unsupported or out of range")
                }
                d.setWhiteBalanceModeLocked(with: .init(redGain: wb.red, greenGain: wb.green, blueGain: wb.blue))
            } else if d.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) { d.whiteBalanceMode = .continuousAutoWhiteBalance }
        }
        try await Task.sleep(nanoseconds: 500_000_000)
        var settled = false
        var stableSamples = 0
        let attempts = Int(max(10, (request.exposureSeconds ?? 0) * 2 + 2) * 10)
        for attempt in 0..<attempts {
            try Task.checkCancellation()
            settled = try await onQueue { [self] in
                if attempt==0 || attempt%10==0 { traceState("settling_sample",requestID:request.id,extra:["attempt":String(attempt)]) }
                guard let d = device else { return false }
                guard !d.isAdjustingExposure && !d.isAdjustingFocus && !d.isAdjustingWhiteBalance else { return false }
                if let seconds = request.exposureSeconds, let iso = request.iso {
                    guard abs(d.exposureDuration.seconds - seconds) <= max(0.00001, seconds * 0.02),
                          abs(d.iso - iso) <= max(1, iso * 0.05) else { return false }
                }
                if let focus = request.focusPosition, abs(d.lensPosition - focus) > 0.02 { return false }
                if let wb = request.whiteBalance {
                    let actual = d.deviceWhiteBalanceGains
                    if abs(actual.redGain - wb.red) > 0.1 || abs(actual.greenGain - wb.green) > 0.1 || abs(actual.blueGain - wb.blue) > 0.1 { return false }
                }
                return true
            }
            stableSamples = settled ? stableSamples + 1 : 0
            if stableSamples >= 3 { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        guard stableSamples >= 3 else { throw CaptureError.unavailable("Camera did not settle or honor the requested controls. Check the supported ranges.") }
        try await onQueue { [self] in
            traceState("controls_settled",requestID:request.id)
            guard let d = device else { throw CaptureError.unavailable("Camera disconnected") }
            try d.lockForConfiguration()
            defer { d.unlockForConfiguration() }
            if request.exposureSeconds == nil {
                guard d.isExposureModeSupported(.locked) else { throw CaptureError.unavailable("Exposure lock is unavailable") }
                d.exposureMode = .locked
                traceState("automatic_exposure_locked",requestID:request.id)
            }
            if d.isFocusModeSupported(.locked) { d.focusMode = .locked }
            if d.isWhiteBalanceModeSupported(.locked) { d.whiteBalanceMode = .locked }
        }
        try await Task.sleep(nanoseconds: UInt64(max(250, request.settleMilliseconds)) * 1_000_000)
        try await onQueue { [self] in
            traceState("preparation_final_state",requestID:request.id)
            guard let d=device, session.isRunning else { throw CaptureError.unavailable("Camera stopped during preparation") }
            let actual=Self.read(d)
            var observed=request
            observed.exposureSeconds=actual.exposure; observed.iso=actual.iso
            observed.focusPosition=actual.focus; observed.whiteBalance=actual.whiteBalance
            preparedKey=key; preparedActualKey=CaptureControlKey(observed)
            preparationTiming=["preparationStartedElapsedNs":started,"controlsLockedElapsedNs":RawCaptureDiagnostics.now,"controlsReused":0]
            traceState("preparation_completed",requestID:request.id)
        }
    }

    func photo(request: CaptureRequest, index: Int, directory: URL,
               exposureFinished: @escaping @Sendable () -> Void = {}) async throws -> FrameRecord {
        try Task.checkCancellation()
        let readinessDeadline = ProcessInfo.processInfo.systemUptime + max(60, (request.exposureSeconds ?? 30) * 4 + 15)
        while true {
            let ready = try await onQueue { [self] in
                guard device != nil, session.isRunning else { throw CaptureError.unavailable("Camera is stopped") }
                return bracketDelegate == nil && delegates.count < 2 && output.captureReadiness == .ready
            }
            if ready { break }
            guard ProcessInfo.processInfo.systemUptime < readinessDeadline else {
                throw CaptureError.unavailable("Camera did not become ready for the next photo")
            }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        try Task.checkCancellation()
        return try await withCheckedThrowingContinuation { continuation in
            queue.async { [self] in
                do {
                    guard let d = device, session.isRunning, bracketDelegate == nil, delegates.count < 2 else { throw CaptureError.unavailable("Camera is busy or stopped") }
                    let settings = try photoSettings(request)
                    let id = settings.uniqueID
                    let capture = StillDelegate(directory: directory, index: index, format: request.format,
                        averaging:request.averaging != nil, requestID: id, exposureFinished: exposureFinished,
                        context:photoContext, preparation:preparationTiming, experiment:experimentEvidence,
                        reading: { Self.read(d) }) { [weak self] result in
                        guard let self else { continuation.resume(with: result); return }
                        self.queue.async {
                            switch result {
                            case .success:
                                self.traceState("photo_completed",requestID:request.id,extra:["index":String(index),"photoID":String(id)])
                            case .failure(let error):
                                self.diagnostics.record("photo_failed",CameraDiagnostics.errorFields(error),requestID:request.id,fault:true)
                            }
                            self.delegates.removeValue(forKey: id)
                            if case .failure = result { self.preparedKey=nil; self.preparedActualKey=nil }
                            continuation.resume(with: result)
                        }
                    }
                    delegates[id] = capture
                    traceState("photo_submitted",requestID:request.id,extra:["index":String(index),"photoID":String(id)])
                    output.capturePhoto(with: settings, delegate: capture)
                    queue.asyncAfter(deadline: .now() + max(60, d.exposureDuration.seconds * 4 + 15)) { [weak self, weak capture] in
                        guard let self, let capture, self.delegates[id] === capture else { return }
                        self.diagnostics.record("session_stop_requested",["reason":"photo_timeout","photoID":String(id)],requestID:request.id,fault:true)
                        self.session.stopRunning()
                        for pending in Array(self.delegates.values) {
                            pending.fail(CaptureError.unavailable("Photo timed out; reconnect the camera before retrying"))
                        }
                    }
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    func pausePreview(_ value: Bool) {
        previewLock.lock(); previewPaused = value; previewLock.unlock()
    }

    func bracket(request: CaptureRequest, indices: [Int], directory: URL) async throws -> [FrameRecord] {
        try Task.checkCancellation()
        let deadline = ProcessInfo.processInfo.systemUptime + max(60, (request.exposureSeconds ?? 30) * 4 + 15)
        while true {
            let ready = try await onQueue { [self] in
                guard device != nil, session.isRunning, experiment == .bracketPair else { throw CaptureError.unavailable("Bracket camera is not running") }
                return delegates.isEmpty && bracketDelegate == nil && output.captureReadiness == .ready
            }
            if ready { break }
            try Task.checkCancellation()
            guard ProcessInfo.processInfo.systemUptime < deadline else { throw CaptureError.unavailable("Camera did not become ready for RAW bracket") }
            try await Task.sleep(nanoseconds:20_000_000)
        }
        try Task.checkCancellation()
        return try await withCheckedThrowingContinuation { continuation in
            queue.async { [self] in
                do {
                    guard let d = device, session.isRunning, delegates.isEmpty, bracketDelegate == nil else {
                        throw CaptureError.unavailable("Camera busy or stopped before RAW bracket")
                    }
                    let settings = try photoSettings(request,bracketCount:indices.count)
                    let id = settings.uniqueID
                    var evidence = experimentEvidence
                    evidence["bracketCount"] = String(indices.count)
                    evidence["singlePhotoReason"] = indices.count == 1 ? "tail_or_only_one_stream_permit" : "none"
                    let capture = BracketDelegate(directory:directory,indices:indices,requestID:id,
                        context:photoContext,preparation:preparationTiming,experiment:evidence,
                        reading:{ Self.read(d) }) { [weak self] result in
                            guard let self else { continuation.resume(with:result); return }
                            self.queue.async {
                                self.bracketDelegate = nil
                                if case .failure(let error) = result {
                                    self.preparedKey = nil; self.preparedActualKey = nil
                                    self.diagnostics.record("bracket_failed",CameraDiagnostics.errorFields(error),requestID:request.id,fault:true)
                                }
                                continuation.resume(with:result)
                            }
                        }
                    bracketDelegate = capture
                    diagnostics.record("bracket_submitted",["photoID":String(id),"firstIndex":String(indices[0]),
                        "count":String(indices.count)],requestID:request.id)
                    output.capturePhoto(with:settings,delegate:capture)
                    queue.asyncAfter(deadline:.now() + max(60,d.exposureDuration.seconds * Double(indices.count) * 4 + 15)) { [weak self, weak capture] in
                        guard let self, let capture, self.bracketDelegate === capture else { return }
                        self.diagnostics.record("session_stop_requested",["reason":"bracket_timeout"],requestID:request.id,fault:true)
                        self.session.stopRunning()
                        capture.fail(CaptureError.unavailable("RAW bracket timed out; reconnect camera"))
                    }
                } catch { continuation.resume(throwing:error) }
            }
        }
    }

    func latestPreview() -> Data? {
        previewLock.lock(); defer { previewLock.unlock() }; return previewData
    }

    func stop(reason: String = "explicit_stop") {
        queue.async {
            self.diagnostics.record("session_stop_requested",["reason":reason])
            self.preparedKey=nil; self.preparedActualKey=nil
            self.preparationToken = nil
            self.session.stopRunning()
            self.bracketDelegate?.fail(CancellationError())
            for pending in Array(self.delegates.values) { pending.fail(CancellationError()) }
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        previewLock.lock(); let paused = previewPaused; previewLock.unlock()
        let time = CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds
        guard !paused, time - previewTime >= 1, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        previewTime = time
        let image = CIImage(cvPixelBuffer: buffer)
        let scale = min(1, 800 / image.extent.width)
        let small = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cg = imageContext.createCGImage(small, from: small.extent), let data = UIImage(cgImage: cg).jpegData(compressionQuality: 0.65) else { return }
        previewLock.lock(); previewData = data; previewLock.unlock()
    }
}

private final class StillDelegate: NSObject, AVCapturePhotoCaptureDelegate {
    private let directory: URL
    private let index: Int
    private let format: CaptureFormat
    private let averaging: Bool
    private let requestID: Int64
    private let exposureFinished: @Sendable () -> Void
    private let submittedElapsedNs: UInt64
    private var exposureStartedElapsedNs: UInt64?
    private var exposureEndedElapsedNs: UInt64?
    private let context: CIContext
    private let preparation: [String:UInt64]
    private let experiment: [String:String]
    private let bracketSequence: Int?
    private let reading: () -> DeviceReading
    private var atShutter: DeviceReading?
    private let lock = NSLock()
    private var completion: ((Result<FrameRecord, Error>) -> Void)?
    private var outcome: Result<FrameRecord, Error>?
    private var processing = false
    private var captureEnded = false
    private var forcedError: Error?

    init(directory: URL, index: Int, format: CaptureFormat, averaging: Bool, requestID: Int64,
         exposureFinished: @escaping @Sendable () -> Void, context: CIContext,
         preparation: [String:UInt64], experiment: [String:String] = [:], bracketSequence: Int? = nil,
         submittedElapsedNs: UInt64 = RawCaptureDiagnostics.now,
         reading: @escaping () -> DeviceReading,
         completion: @escaping (Result<FrameRecord, Error>) -> Void) {
        self.directory = directory; self.index = index; self.format = format; self.averaging = averaging
        self.requestID = requestID; self.exposureFinished = exposureFinished
        self.context=context; self.preparation=preparation
        self.experiment=experiment; self.bracketSequence=bracketSequence
        self.submittedElapsedNs=submittedElapsedNs
        self.reading = reading; self.completion = completion
    }

    func photoOutput(_ output: AVCapturePhotoOutput, willCapturePhotoFor resolvedSettings: AVCaptureResolvedPhotoSettings) {
        guard resolvedSettings.uniqueID == requestID else { fail(CaptureError.invalid("Photo request identity mismatch")); return }
        lock.lock(); atShutter = reading(); exposureStartedElapsedNs = RawCaptureDiagnostics.now; lock.unlock()
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didCapturePhotoFor resolvedSettings: AVCaptureResolvedPhotoSettings) {
        guard resolvedSettings.uniqueID == requestID else { fail(CaptureError.invalid("Photo request identity mismatch")); return }
        lock.lock()
        let notify = completion != nil && exposureEndedElapsedNs == nil && forcedError == nil
        exposureEndedElapsedNs = RawCaptureDiagnostics.now
        lock.unlock()
        if notify { exposureFinished() }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        lock.lock()
        guard completion != nil, !processing, !captureEnded else { lock.unlock(); return }
        processing = true
        lock.unlock()
        defer {
            lock.lock(); processing = false; lock.unlock()
            finishIfReady()
        }
        do {
            guard photo.resolvedSettings.uniqueID == requestID else { throw CaptureError.invalid("Photo request identity mismatch") }
            if let error { throw error }
            let expectedRAW = format != .jpeg
            let start = RawCaptureDiagnostics.now
            var before: NativeRawSnapshot?
            var diagnosticError: String?
            if format == .bayerRAW, let buffer = photo.pixelBuffer {
                do { before = try RawCaptureDiagnostics.snapshot(buffer,retain:averaging) }
                catch { if averaging { throw error }; diagnosticError = error.localizedDescription }
            } else if format == .bayerRAW {
                diagnosticError = "Native Bayer pixel buffer unavailable"
            }
            guard photo.isRawPhoto == expectedRAW, let data = photo.fileDataRepresentation() else {
                throw CaptureError.unavailable("Camera returned an unexpected photo format")
            }
            if expectedRAW {
                guard let buffer = photo.pixelBuffer else { throw CaptureError.unavailable("Camera omitted the RAW pixel buffer") }
                let pixelFormat = CVPixelBufferGetPixelFormatType(buffer)
                let matches = format == .appleProRAW ? AVCapturePhotoOutput.isAppleProRAWPixelFormat(pixelFormat)
                    : AVCapturePhotoOutput.isBayerRAWPixelFormat(pixelFormat)
                guard matches else { throw CaptureError.unavailable("Camera returned the wrong RAW format") }
            }
            let base = String(format: "frame-%04d", index)
            let encoded = RawCaptureDiagnostics.now
            let file = try write(data, name: base + (expectedRAW ? ".dng" : ".jpg"), mediaType: expectedRAW ? "image/dng" : "image/jpeg")
            let originalWrittenElapsedNs = RawCaptureDiagnostics.now
            var metadataObject = jsonValue(photo.metadata) as? [String:Any] ?? [:]
            if !experiment.isEmpty { metadataObject["captureExperiment"] = experiment }
            if let before, let buffer = photo.pixelBuffer {
                do {
                    var diagnostics = try RawCaptureDiagnostics.finish(before:before,
                        after:RawCaptureDiagnostics.snapshot(buffer,retain:false),data:data,file:directory.appendingPathComponent(file.name),
                        encodedReceipt:file, stage:averaging ? directory.appendingPathComponent(base+".raw16") : nil,start:start,encoded:encoded)
                    diagnostics["requestSubmittedElapsedNs"]=submittedElapsedNs
                    diagnostics["imageArrivedElapsedNs"]=start
                    for (key,value) in preparation { diagnostics[key]=value }
                    diagnostics["photoTimestampSeconds"] = photo.timestamp.seconds
                    diagnostics["resolvedSettingsID"] = photo.resolvedSettings.uniqueID
                    metadataObject["captureDiagnostics"] = diagnostics
                } catch { if averaging { throw error }; diagnosticError = error.localizedDescription }
            } else if averaging { throw CaptureError.unavailable("Native Bayer diagnostics unavailable") }
            if let diagnosticError {
                metadataObject["captureDiagnostics"] = ["diagnosticsVersion":3,"platform":"ios",
                    "sampleIntegrity":["assessment":"incomplete_diagnostics","findings":[diagnosticError]]]
            }
            lock.lock()
            metadataObject["captureTiming"] = ["policy": bracketSequence != nil ? "native-raw-bracket-v1" : (averaging ? "sequential-v1" : "two-photo-after-exposure-v1"),
                "requestID": requestID, "submittedElapsedNs": submittedElapsedNs,
                "bracketSequence":bracketSequence.map { $0 as Any } ?? NSNull(),
                "deviceReadingSource":bracketSequence == nil ? "shutter_callback_or_processing_fallback" : "processing_callback",
                "exposureStartedElapsedNs": exposureStartedElapsedNs.map { $0 as Any } ?? NSNull(),
                "exposureEndedElapsedNs": exposureEndedElapsedNs.map { $0 as Any } ?? NSNull(),
                "processingCallbackElapsedNs": start, "encodedElapsedNs": encoded,
                "originalWrittenElapsedNs": originalWrittenElapsedNs]
            lock.unlock()
            let metadata = try JSONSerialization.data(withJSONObject:metadataObject, options:[.prettyPrinted,.sortedKeys])
            let metadataFile = try write(metadata, name: base + ".json", mediaType: "application/json")
            var previewFile: CapturedFile?
            if let buffer = photo.previewPixelBuffer {
                let image = CIImage(cvPixelBuffer: buffer)
                if let cg = context.createCGImage(image, from: image.extent), let jpeg = UIImage(cgImage: cg).jpegData(compressionQuality: 0.85) {
                    previewFile = try write(jpeg, name: base + ".preview.jpg", mediaType: "image/jpeg")
                }
            }
            let exif = photo.metadata[kCGImagePropertyExifDictionary as String] as? [String: Any]
            let exposure = (exif?[kCGImagePropertyExifExposureTime as String] as? NSNumber)?.doubleValue
            let iso = (exif?[kCGImagePropertyExifISOSpeedRatings as String] as? [NSNumber])?.first?.doubleValue
            let observed = atShutter ?? reading()
            let size = expectedRAW ? photo.resolvedSettings.rawPhotoDimensions : photo.resolvedSettings.photoDimensions
            outcome = .success(FrameRecord(index: index, file: file, metadataFile: metadataFile, previewFile: previewFile,
                width: Int(size.width), height: Int(size.height), timestampSeconds: photo.timestamp.seconds,
                actualExposureSeconds: exposure, actualISO: iso, deviceExposureSeconds: observed.exposure,
                deviceISO: observed.iso, lensPosition: observed.focus, whiteBalance: observed.whiteBalance,
                isBayerRAW: format == .bayerRAW, isAppleProRAW: format == .appleProRAW))
        } catch { outcome = .failure(error) }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        guard resolvedSettings.uniqueID == requestID else { fail(CaptureError.invalid("Photo request identity mismatch")); return }
        lock.lock(); captureEnded = true
        if let error { forcedError = error }
        lock.unlock()
        finishIfReady()
    }

    func fail(_ error: Error) {
        lock.lock(); forcedError = error; captureEnded = true; lock.unlock()
        finishIfReady()
    }
    private func finishIfReady() {
        lock.lock()
        guard captureEnded, !processing, let callback = completion else { lock.unlock(); return }
        let result = forcedError.map { Result<FrameRecord, Error>.failure($0) }
            ?? outcome ?? .failure(CaptureError.unavailable("Camera returned no photo"))
        completion = nil
        lock.unlock()
        callback(result)
    }

    private func write(_ data: Data, name: String, mediaType: String) throws -> CapturedFile {
        try data.write(to: directory.appendingPathComponent(name), options: .atomic)
        return CapturedFile(name: name, bytes: data.count,
            sha256: SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(), mediaType: mediaType)
    }

    private func jsonValue(_ value: Any) -> Any {
        if let dict = value as? [String: Any] { return dict.mapValues(jsonValue) }
        if let array = value as? [Any] { return array.map(jsonValue) }
        if let data = value as? Data { return data.base64EncodedString() }
        if let number = value as? NSNumber { return number.doubleValue.isFinite ? number : NSNull() }
        if let text = value as? String { return text }
        if value is NSNull { return value }
        return String(describing: value)
    }
}

/// One AVFoundation request, one/two independent RAWs. Sequence count, request ID,
/// and actual sensor timestamps associate callbacks; a group event is never
/// fabricated into a per-photo exposure timestamp. Child writers drain on failure.
private final class BracketDelegate: NSObject, AVCapturePhotoCaptureDelegate {
    let count: Int
    private let requestID: Int64
    private var identity: BracketCaptureIdentity
    private let lock = NSLock()
    private var children: [StillDelegate] = []
    private var results: [Int:Result<FrameRecord,Error>] = [:]
    private var ended = false
    private var failure: Error?
    private var completion: ((Result<[FrameRecord],Error>) -> Void)?

    init(directory: URL, indices: [Int], requestID: Int64, context: CIContext,
         preparation: [String:UInt64], experiment: [String:String], reading: @escaping () -> DeviceReading,
         completion: @escaping (Result<[FrameRecord],Error>) -> Void) {
        count = indices.count; self.requestID = requestID
        identity = BracketCaptureIdentity(requestID:requestID,count:indices.count)
        self.completion = completion
        super.init()
        let submitted = RawCaptureDiagnostics.now
        children = indices.enumerated().map { position,index in
            StillDelegate(directory:directory,index:index,format:.bayerRAW,averaging:false,requestID:requestID,
                exposureFinished:{},context:context,preparation:preparation,experiment:experiment,
                bracketSequence:position+1,submittedElapsedNs:submitted,reading:reading) { [weak self] result in
                    guard let self else { return }
                    self.lock.lock(); self.results[position] = result; self.lock.unlock()
                    self.finishIfReady()
                }
        }
    }
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        lock.lock()
        guard !ended, completion != nil else { lock.unlock(); return }
        do {
            if let error { throw error }
            try identity.accept(requestID:photo.resolvedSettings.uniqueID,sequence:photo.sequenceCount,
                photoCount:photo.photoCount,expectedCount:photo.resolvedSettings.expectedPhotoCount,
                timestamp:photo.timestamp.seconds,raw:photo.isRawPhoto)
            let child = children[photo.sequenceCount-1]
            lock.unlock()
            child.photoOutput(output,didFinishProcessingPhoto:photo,error:nil)
        } catch { lock.unlock(); fail(error) }
    }
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        lock.lock()
        let complete = identity.complete && resolvedSettings.uniqueID == requestID && resolvedSettings.expectedPhotoCount == count
        ended = true
        lock.unlock()
        if let error { fail(error); return }
        guard complete else { fail(CaptureError.invalid("Incomplete RAW bracket result")); return }
        for child in children { child.photoOutput(output,didFinishCaptureFor:resolvedSettings,error:nil) }
        finishIfReady()
    }
    func fail(_ error: Error) {
        lock.lock(); ended = true; if failure == nil { failure = error }; lock.unlock()
        for child in children { child.fail(error) }
        finishIfReady()
    }
    private func finishIfReady() {
        lock.lock()
        guard ended, results.count == count, let callback = completion else { lock.unlock(); return }
        let result: Result<[FrameRecord],Error>
        if let failure { result = .failure(failure) }
        else { result = Result { try (0..<count).map { try results[$0]!.get() } } }
        completion = nil
        lock.unlock()
        callback(result)
    }
}
