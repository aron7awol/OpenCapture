import Foundation
import Dispatch

public struct CameraDiagnosticEvent: Codable, Sendable {
    public let sequence: UInt64
    public let elapsedNs: UInt64
    public let unixMs: Int64
    public let type: String
    public let requestID: String?
    public var fields: [String: String]
}

public struct CameraDiagnosticSnapshot: Codable, Sendable {
    public var schemaVersion = 1
    public var platform = "ios"
    public var policy = "ios-session-v1"
    public let request: [String: String]
    public let startedSequence: UInt64
    public let priorEvents: [CameraDiagnosticEvent]
    public var events: [CameraDiagnosticEvent]
    public var firstFault: CameraDiagnosticEvent?
}

/// Small in-memory evidence only. Never stores context, credentials, pixels or NSError userInfo.
/// Notification callbacks never wait for a camera queue or perform disk/network I/O.
public final class CameraDiagnostics: @unchecked Sendable {
    private let lock = NSLock()
    private var sequence: UInt64 = 0
    private var recent: [CameraDiagnosticEvent] = []
    private var active: CameraDiagnosticSnapshot?
    private var completed: [CameraDiagnosticSnapshot] = []
    public init() {}

    private static func bounded(_ value: String, bytes: Int) -> String {
        var result = ""
        for character in value {
            let next = String(character)
            if result.utf8.count + next.utf8.count > bytes { break }
            result += next
        }
        return result
    }
    private func append(_ type: String, _ fields: [String:String], requestID: String?, fault: Bool) {
        sequence += 1
        var boundedFields: [String:String] = [:]
        for key in fields.keys.sorted().prefix(24) {
            boundedFields[Self.bounded(key,bytes:40)] = Self.bounded(fields[key] ?? "",bytes:128)
        }
        var event = CameraDiagnosticEvent(sequence:sequence,elapsedNs:DispatchTime.now().uptimeNanoseconds,
            unixMs:Int64(Date().timeIntervalSince1970*1000),type:Self.bounded(type,bytes:64),
            requestID:requestID.map { Self.bounded($0,bytes:64) },fields:boundedFields)
        // Bound encoded bytes too, including escaping of unusual OS error domains.
        while ((try? JSONEncoder().encode(event).count) ?? Int.max) > 2048, let key=event.fields.keys.sorted().last {
            event.fields.removeValue(forKey:key)
        }
        recent.append(event); if recent.count > 32 { recent.removeFirst(recent.count-32) }
        if active?.request["id"] == requestID, active != nil {
            active?.events.append(event)
            let excess=active!.events.count-32
            if excess > 0 { active!.events.removeFirst(excess) }
            if fault, active?.firstFault == nil { active?.firstFault=event }
        }
    }
    public func begin(_ request: CaptureRequest) {
        lock.lock(); defer { lock.unlock() }
        if let active { completed.append(active) }
        if completed.count > 8 { completed.removeFirst(completed.count-8) }
        // Explicit allowlist: opaque controller context must never enter diagnostic records.
        var fields = ["id":request.id,"operation":request.operation?.rawValue ?? "capture",
                      "format":request.format.rawValue,"quality":request.quality.rawValue,
                      "frameCount":String(request.frameCount),"settleMilliseconds":String(request.settleMilliseconds),
                      "streaming":request.streaming?.mode ?? "off","averaging":request.averaging?.mode ?? "off"]
        fields["exposureSeconds"]=request.exposureSeconds.map { String($0) } ?? "automatic"
        fields["captureExperiment"] = request.captureExperiment?.rawValue ?? "standard"
        fields["iso"]=request.iso.map { String($0) } ?? "automatic"
        fields["focusPosition"]=request.focusPosition.map { String($0) } ?? "automatic"
        if let wb=request.whiteBalance { fields["whiteBalance"]="\(wb.red),\(wb.green),\(wb.blue)" }
        else { fields["whiteBalance"]="automatic" }
        active=CameraDiagnosticSnapshot(request:fields,startedSequence:sequence+1,
            priorEvents:Array(recent.suffix(8)),events:[])
        append("operation_started",[:],requestID:request.id,fault:false)
    }
    public func record(_ type: String, _ fields: [String:String] = [:], requestID: String? = nil, fault: Bool = false) {
        lock.lock(); defer { lock.unlock() }
        append(type,fields,requestID:requestID ?? active?.request["id"],fault:fault)
    }
    public func finish(_ requestID: String, error: Error? = nil) {
        lock.lock(); defer { lock.unlock() }
        guard active?.request["id"] == requestID else { return }
        append(error == nil ? "operation_completed" : "operation_failed",
               error.map(Self.errorFields) ?? [:],requestID:requestID,fault:error != nil)
        if let active { completed.append(active) }
        active=nil
        if completed.count > 8 { completed.removeFirst(completed.count-8) }
    }
    public func snapshot(_ requestID: String) -> CameraDiagnosticSnapshot? {
        lock.lock(); defer { lock.unlock() }
        if active?.request["id"] == requestID { return active }
        return completed.last { $0.request["id"] == requestID }
    }
    public static func errorFields(_ error: Error) -> [String:String] {
        var fields: [String:String] = [:]
        var current: NSError? = error as NSError
        for depth in 0..<4 {
            guard let value=current else { break }
            fields["error\(depth)Domain"]=bounded(value.domain,bytes:128)
            fields["error\(depth)Code"]=String(value.code)
            current=value.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return fields
    }
}
