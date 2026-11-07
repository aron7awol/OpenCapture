import Foundation

public enum CaptureFormat: String, Codable, CaseIterable, Sendable {
    case bayerRAW = "bayerRaw"
    case jpeg
}

public enum CaptureQuality: String, Codable, CaseIterable, Sendable {
    case speed, quality
}

public struct WhiteBalance: Codable, Equatable, Sendable {
    public var red: Float
    public var green: Float
    public var blue: Float
}

public struct CaptureRequest: Codable, Equatable, Sendable {
    public var protocolVersion = 1
    public var id: String
    public var format: CaptureFormat = .bayerRAW
    public var quality: CaptureQuality = .speed
    public var frameCount = 4
    public var exposureSeconds: Double? = nil
    public var iso: Float? = nil
    public var focusPosition: Float? = nil
    public var whiteBalance: WhiteBalance? = nil
    public var settleMilliseconds = 500
    public var context: [String: String] = [:]

    public init(id: String = UUID().uuidString.lowercased()) { self.id = id }

    public func validate() throws {
        guard protocolVersion == 1, UUID(uuidString: id) != nil else {
            throw CaptureError.invalid("Invalid protocol version or request ID")
        }
        guard (1...32).contains(frameCount), (0...10000).contains(settleMilliseconds) else {
            throw CaptureError.invalid("Frame count must be 1...32; settle time 0...10000 ms")
        }
        guard format != .bayerRAW || quality == .speed else {
            throw CaptureError.invalid("Bayer RAW requires speed priority")
        }
        guard (exposureSeconds == nil) == (iso == nil) else {
            throw CaptureError.invalid("Manual exposure requires both shutter time and ISO")
        }
        if let value = exposureSeconds, !value.isFinite || value <= 0 || value > 30 {
            throw CaptureError.invalid("Invalid exposure time")
        }
        if let value = iso, !value.isFinite || value < 1 || value > 204800 {
            throw CaptureError.invalid("Invalid ISO")
        }
        if let value = focusPosition, !value.isFinite || !(0...1).contains(value) {
            throw CaptureError.invalid("Focus position must be 0...1")
        }
        if let wb = whiteBalance {
            guard [wb.red, wb.green, wb.blue].allSatisfy({ $0.isFinite && (1...64).contains($0) }) else {
                throw CaptureError.invalid("Invalid white balance gains")
            }
        }
        guard context.count <= 16, context.allSatisfy({ $0.key.utf8.count <= 64 && $0.value.utf8.count <= 256 }) else {
            throw CaptureError.invalid("Context is too large")
        }
    }

    public static func decode(_ data: Data) throws -> CaptureRequest {
        guard data.count <= 16384,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys).isSubset(of: ["protocolVersion", "id", "format", "quality", "frameCount", "exposureSeconds", "iso", "focusPosition", "whiteBalance", "settleMilliseconds", "context"]) else {
            throw CaptureError.invalid("Malformed request or unknown field")
        }
        if let wb = object["whiteBalance"] as? [String: Any], Set(wb.keys) != ["red", "green", "blue"] {
            throw CaptureError.invalid("Unknown white balance field")
        }
        let result = try JSONDecoder().decode(Self.self, from: data)
        try result.validate()
        return result
    }
}

public struct CaptureLease: Codable, Sendable {
    public var protocolVersion: Int
    public var request: CaptureRequest?
    public var leaseToken: String?
}

public struct CameraCapabilities: Codable, Sendable {
    public var protocolVersion = 1
    public var cameraID: String
    public var name: String
    public var formats: [CaptureFormat]
    public var minISO: Float
    public var maxISO: Float
    public var minExposureSeconds: Double
    public var maxExposureSeconds: Double
    public var manualExposure: Bool
    public var manualFocus: Bool
    public var manualWhiteBalance: Bool
    public var maxWhiteBalanceGain: Float
    public var maxPhotoWidth: Int
    public var maxPhotoHeight: Int
    public var osVersion: String
    public var appVersion: String
}

public struct CapturedFile: Codable, Equatable, Sendable {
    public var name: String
    public var bytes: Int
    public var sha256: String
    public var mediaType: String
}

public struct FrameRecord: Codable, Sendable {
    public var index: Int
    public var file: CapturedFile
    public var metadataFile: CapturedFile
    public var previewFile: CapturedFile?
    public var width: Int
    public var height: Int
    public var timestampSeconds: Double
    public var actualExposureSeconds: Double?
    public var actualISO: Double?
    public var deviceExposureSeconds: Double
    public var deviceISO: Float
    public var lensPosition: Float
    public var whiteBalance: WhiteBalance
    public var isBayerRAW: Bool
}

public struct CaptureManifest: Codable, Sendable {
    public var protocolVersion = 1
    public var request: CaptureRequest
    public var camera: CameraCapabilities
    public var frames: [FrameRecord]
    public var startedAt: String
    public var completedAt: String
    public var processing: String
}

public enum CaptureError: LocalizedError {
    case invalid(String)
    case unavailable(String)
    case transport(String)
    public var errorDescription: String? {
        switch self {
        case .invalid(let message), .unavailable(let message), .transport(let message): return message
        }
    }
}

public enum EndpointPolicy {
    public static func controllerURL(_ text: String) throws -> URL {
        guard let parts = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              let host = parts.host?.lowercased(), !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.path.isEmpty || parts.path == "/",
              parts.port == nil || (1...65535).contains(parts.port!),
              let url = parts.url else { throw CaptureError.invalid("Enter a server URL without a path or credentials") }
        guard parts.scheme == "https" || (parts.scheme == "http" && isLocalHost(host)) else {
            throw CaptureError.invalid("HTTP is restricted to private IPv4 addresses or .local names; otherwise use HTTPS")
        }
        return url
    }

    public static func isLocalHost(_ host: String) -> Bool {
        if host == "localhost" || host.hasSuffix(".local") { return true }
        let segments = host.split(separator: ".", omittingEmptySubsequences: false)
        guard segments.count == 4 else { return false }
        let octets = segments.compactMap { UInt8($0) }
        guard octets.count == 4 else { return false }
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && (16...31).contains(octets[1]))
    }
}

public enum ReleaseVersion {
    public static func newer(version: String, build: String, than current: String, build currentBuild: String) -> Bool {
        let comparison = version.compare(current, options: .numeric)
        return comparison == .orderedDescending ||
            (comparison == .orderedSame && build.compare(currentBuild, options: .numeric) == .orderedDescending)
    }
}
