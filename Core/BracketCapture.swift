import Foundation

/// Validate identity before a bracket callback is allowed to write an original.
/// Kept independent of AVFoundation so duplicate/stale/out-of-order callbacks are testable.
public struct BracketCaptureIdentity {
    private let requestID: Int64
    private let count: Int
    private var timestamps: [Int: Double] = [:]
    public init(requestID: Int64, count: Int) {
        precondition((1...2).contains(count))
        self.requestID = requestID; self.count = count
    }
    public mutating func accept(requestID: Int64, sequence: Int, photoCount: Int,
                                expectedCount: Int, timestamp: Double, raw: Bool) throws {
        guard requestID == self.requestID, expectedCount == count, raw,
              (1...count).contains(sequence), photoCount == sequence,
              timestamps[sequence] == nil, timestamp.isFinite, timestamp > 0,
              timestamps.allSatisfy({ $0.key < sequence ? $0.value < timestamp : $0.value > timestamp }) else {
            throw CaptureError.invalid("Invalid, duplicate or stale RAW bracket result")
        }
        timestamps[sequence] = timestamp
    }
    public var complete: Bool { timestamps.count == count }
}

/// Reserve every exposure before submitting a one/two-photo native bracket.
/// A second permit is nonblocking: a previous result may need a verdict first.
@MainActor public final class BracketCapturePipeline<Value: Sendable> {
    private var pending: Task<[Value], Error>?
    public init() {}
    public func run(limit: Int,
                    reserve: @escaping @MainActor (Bool) async throws -> Bool,
                    finished: @escaping @MainActor () -> Bool,
                    capture: @escaping @MainActor ([Int]) async throws -> [Value],
                    consume: @escaping @MainActor (Int, Value) async throws -> Void) async throws {
        precondition((1...64).contains(limit) && pending == nil)
        try await withTaskCancellationHandler {
            do {
                var index = 1
                while index <= limit && !finished() {
                    try Task.checkCancellation()
                    guard try await reserve(true) else { break }
                    var indices = [index]
                    if index < limit && !finished(), try await reserve(false) { indices.append(index + 1) }
                    try Task.checkCancellation()
                    if finished() { break }
                    let batch = indices
                    let task = Task { @MainActor in try await capture(batch) }
                    pending = task
                    let values = try await task.value
                    pending = nil
                    try Task.checkCancellation()
                    guard values.count == indices.count else { throw CaptureError.invalid("Incomplete RAW bracket") }
                    for (frameIndex, value) in zip(indices, values) {
                        if finished() { break }
                        try await consume(frameIndex, value)
                    }
                    index += indices.count
                }
            } catch {
                pending?.cancel()
                if let pending { _ = try? await pending.value }
                pending = nil
                throw error
            }
        } onCancel: { Task { @MainActor in self.pending?.cancel() } }
    }
}
