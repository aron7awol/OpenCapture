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
    private var delegate: StillDelegate?
    private var previewTime = 0.0
    private let previewLock = NSLock()
    private var previewData: Data?
    private var previewPaused = false
    private var standardDimensions = CMVideoDimensions(width: 0, height: 0)
    private var proRAWDimensions: CMVideoDimensions?

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
            guard delegate == nil else { throw CaptureError.unavailable("A photo is still in flight") }
            preparedKey=nil; preparedActualKey=nil; preparationTiming=[:]
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
                session.startRunning()
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
            captureStreamingModes:["originals-v1"], optionalPhotoPreview:true)
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
            guard let key, let d=device, session.isRunning,
                  key == preparedKey || key == preparedActualKey else { return false }
            return reusable(d,key)
        }
        if canReuse {
            // Physical pattern settling remains independent of control reuse.
            try await Task.sleep(nanoseconds:UInt64(max(250,request.settleMilliseconds))*1_000_000)
            let valid=try await onQueue { [self] in
                guard let key, let d=device, session.isRunning, reusable(d,key) else { return false }
                preparationTiming=["preparationStartedElapsedNs":started,"controlsLockedElapsedNs":RawCaptureDiagnostics.now,"controlsReused":1]
                return true
            }
            if valid { return }
        }
        try await onQueue { [self] in
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
                d.setExposureModeCustom(duration: CMTime(seconds: seconds, preferredTimescale: 1_000_000_000), iso: iso)
            } else {
                guard d.isExposureModeSupported(.continuousAutoExposure) else { throw CaptureError.unavailable("Auto exposure is unavailable") }
                d.exposureMode = .continuousAutoExposure
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
        for _ in 0..<attempts {
            try Task.checkCancellation()
            settled = try await onQueue { [self] in
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
            guard let d = device else { throw CaptureError.unavailable("Camera disconnected") }
            try d.lockForConfiguration()
            defer { d.unlockForConfiguration() }
            if request.exposureSeconds == nil {
                guard d.isExposureModeSupported(.locked) else { throw CaptureError.unavailable("Exposure lock is unavailable") }
                d.exposureMode = .locked
            }
            if d.isFocusModeSupported(.locked) { d.focusMode = .locked }
            if d.isWhiteBalanceModeSupported(.locked) { d.whiteBalanceMode = .locked }
        }
        try await Task.sleep(nanoseconds: UInt64(max(250, request.settleMilliseconds)) * 1_000_000)
        try await onQueue { [self] in
            guard let d=device, session.isRunning else { throw CaptureError.unavailable("Camera stopped during preparation") }
            let actual=Self.read(d)
            var observed=request
            observed.exposureSeconds=actual.exposure; observed.iso=actual.iso
            observed.focusPosition=actual.focus; observed.whiteBalance=actual.whiteBalance
            preparedKey=key; preparedActualKey=CaptureControlKey(observed)
            preparationTiming=["preparationStartedElapsedNs":started,"controlsLockedElapsedNs":RawCaptureDiagnostics.now,"controlsReused":0]
        }
    }

    func photo(request: CaptureRequest, index: Int, directory: URL) async throws -> FrameRecord {
        try Task.checkCancellation()
        return try await withCheckedThrowingContinuation { continuation in
            queue.async { [self] in
                do {
                    guard let d = device, session.isRunning, delegate == nil else { throw CaptureError.unavailable("Camera is busy or stopped") }
                    let settings: AVCapturePhotoSettings
                    if request.format != .jpeg {
                        let query: (OSType) -> Bool = request.format == .appleProRAW
                            ? AVCapturePhotoOutput.isAppleProRAWPixelFormat : AVCapturePhotoOutput.isBayerRAWPixelFormat
                        guard let raw = output.availableRawPhotoPixelFormatTypes.first(where: {
                            query($0) && (request.averaging == nil || RawCaptureDiagnostics.formats[$0] != nil)
                        }) else {
                            throw CaptureError.unavailable("\(request.format.title) is unavailable")
                        }
                        settings = AVCapturePhotoSettings(rawPixelFormatType: raw)
                    } else { settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg]) }
                    settings.maxPhotoDimensions = request.format == .appleProRAW ? (proRAWDimensions ?? standardDimensions) : standardDimensions
                    settings.photoQualityPrioritization = request.quality == .speed ? .speed : .quality
                    if output.supportedFlashModes.contains(.off) { settings.flashMode = .off }
                    if request.includePreview != false, let previewType = settings.availablePreviewPhotoPixelFormatTypes.first {
                        settings.previewPhotoFormat = [kCVPixelBufferPixelFormatTypeKey as String: previewType,
                            kCVPixelBufferWidthKey as String: 640, kCVPixelBufferHeightKey as String: 480]
                    }
                    let capture = StillDelegate(directory: directory, index: index, format: request.format,
                        averaging:request.averaging != nil, context:photoContext, preparation:preparationTiming,
                        reading: { Self.read(d) }) { [weak self] result in
                        self?.queue.async {
                            self?.delegate = nil
                            if case .failure = result { self?.preparedKey=nil; self?.preparedActualKey=nil }
                        }
                        continuation.resume(with: result)
                    }
                    delegate = capture
                    output.capturePhoto(with: settings, delegate: capture)
                    queue.asyncAfter(deadline: .now() + max(60, d.exposureDuration.seconds * 4 + 15)) { [weak self, weak capture] in
                        guard let self, let capture, self.delegate === capture else { return }
                        self.session.stopRunning()
                        capture.fail(CaptureError.unavailable("Photo timed out; reconnect the camera before retrying"))
                    }
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    func pausePreview(_ value: Bool) {
        previewLock.lock(); previewPaused = value; previewLock.unlock()
    }

    func latestPreview() -> Data? {
        previewLock.lock(); defer { previewLock.unlock() }; return previewData
    }

    func stop() { queue.async { self.preparedKey=nil; self.preparedActualKey=nil; self.session.stopRunning() } }

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
    private let context: CIContext
    private let preparation: [String:UInt64]
    private let submitted = RawCaptureDiagnostics.now
    private let reading: () -> DeviceReading
    private var atShutter: DeviceReading?
    private let lock = NSLock()
    private var completion: ((Result<FrameRecord, Error>) -> Void)?
    private var outcome: Result<FrameRecord, Error>?

    init(directory: URL, index: Int, format: CaptureFormat, averaging: Bool, context: CIContext,
         preparation: [String:UInt64], reading: @escaping () -> DeviceReading,
         completion: @escaping (Result<FrameRecord, Error>) -> Void) {
        self.directory = directory; self.index = index; self.format = format; self.averaging = averaging
        self.context=context; self.preparation=preparation
        self.reading = reading; self.completion = completion
    }

    func photoOutput(_ output: AVCapturePhotoOutput, willCapturePhotoFor resolvedSettings: AVCaptureResolvedPhotoSettings) { atShutter = reading() }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        do {
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
            var metadataObject = jsonValue(photo.metadata) as? [String:Any] ?? [:]
            if let before, let buffer = photo.pixelBuffer {
                do {
                    var diagnostics = try RawCaptureDiagnostics.finish(before:before,
                        after:RawCaptureDiagnostics.snapshot(buffer,retain:false),data:data,file:directory.appendingPathComponent(file.name),
                        encodedReceipt:file, stage:averaging ? directory.appendingPathComponent(base+".raw16") : nil,start:start,encoded:encoded)
                    diagnostics["requestSubmittedElapsedNs"]=submitted
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
        if let error { fail(error) }
        else { finish(outcome ?? .failure(CaptureError.unavailable("Camera returned no photo"))) }
    }

    func fail(_ error: Error) { finish(.failure(error)) }
    private func finish(_ result: Result<FrameRecord, Error>) {
        lock.lock(); let callback = completion; completion = nil; lock.unlock()
        callback?(result)
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
