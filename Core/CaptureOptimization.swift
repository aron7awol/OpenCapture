import Foundation

/// Exact requested keys prevent a small intentional control change being ignored.
public struct CaptureControlKey: Equatable, Sendable {
    public let format: CaptureFormat
    public let quality: CaptureQuality
    public let exposure: Double
    public let iso: Float
    public let focus: Float
    public let whiteBalance: WhiteBalance
    public init?(_ request: CaptureRequest) {
        guard let exposure=request.exposureSeconds, let iso=request.iso,
              let focus=request.focusPosition, let wb=request.whiteBalance else { return nil }
        self.format=request.format; self.quality=request.quality
        self.exposure=exposure; self.iso=iso; self.focus=focus; self.whiteBalance=wb
    }
    public func honors(exposure: Double, iso: Float, focus: Float, whiteBalance: WhiteBalance) -> Bool {
        abs(exposure-self.exposure) <= max(0.00001,self.exposure*0.02) &&
        abs(iso-self.iso) <= max(1,self.iso*0.05) && abs(focus-self.focus) <= 0.02 &&
        abs(whiteBalance.red-self.whiteBalance.red) <= 0.1 &&
        abs(whiteBalance.green-self.whiteBalance.green) <= 0.1 &&
        abs(whiteBalance.blue-self.whiteBalance.blue) <= 0.1
    }
}

/// A single inspection overlaps the next photo, never an unbounded pixel queue.
@MainActor public final class FrameProcessor<Value: Sendable> {
    private var task: Task<Value,Error>?
    public var hasPending: Bool { task != nil }
    public init() {}
    public func submit(_ work: @escaping @Sendable () throws -> Value) throws {
        guard task == nil else { throw CaptureError.invalid("Inspection queue is full") }
        task = Task.detached(priority:.userInitiated,operation:work)
    }
    public func take() async throws -> Value? {
        guard let pending=task else { return nil }
        defer { task=nil }
        return try await withTaskCancellationHandler(operation:{try await pending.value},onCancel:{pending.cancel()})
    }
    public func cancelAndWait() async {
        guard let pending=task else { return }
        pending.cancel()
        _ = try? await pending.value
        task=nil
    }
}
