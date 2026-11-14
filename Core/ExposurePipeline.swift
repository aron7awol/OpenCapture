import Foundation

/// Ordered file consumers with at most two unfinished photos. The capture
/// callback signals exposure completion, not permission to publish a partial file.
@MainActor public final class ExposurePipeline<Value: Sendable> {
    private var tasks: [Task<Value, Error>] = []
    private var stopped = false
    public init() {}

    public func run(limit: Int, overlap: Bool,
                    reserve: @escaping @MainActor (Bool) async throws -> Bool,
                    finished: @escaping @MainActor () -> Bool,
                    capture: @escaping @MainActor (Int, @escaping @Sendable () -> Void) async throws -> Value,
                    consume: @escaping @MainActor (Int, Value) async throws -> Bool) async throws {
        precondition((1...64).contains(limit) && tasks.isEmpty && !stopped)
        try await withTaskCancellationHandler {
            do {
                var next: (gate: ExposureGate, task: Task<Value, Error>)?
                for index in 1...limit {
                    try Task.checkCancellation()
                    if stopped || finished() { break }
                    if next == nil {
                        guard try await reserve(true) else { break }
                        try Task.checkCancellation()
                        if stopped || finished() { break }
                        next = start(index, capture)
                    }
                    let current = next!
                    next = nil
                    let exposed = await current.gate.wait()
                    try Task.checkCancellation()
                    if stopped || finished() { break }
                    // Never block admission here: the current file may need to be
                    // published before the controller can return another permit.
                    if overlap && exposed && index < limit {
                        if try await reserve(false) {
                            try Task.checkCancellation()
                            if !stopped && !finished() { next = start(index + 1, capture) }
                        }
                    }
                    let value: Value
                    do { value = try await current.task.value }
                    catch { if finished() { break }; throw error }
                    try Task.checkCancellation()
                    if finished() { break }
                    if !(try await consume(index, value)) { break }
                }
                cancelPending()
                await drain()
            } catch {
                cancelPending()
                await drain() // Staging cannot be deleted while an admitted writer runs.
                throw error
            }
        } onCancel: {
            Task { @MainActor in self.cancelPending() }
        }
    }

    private func start(_ index: Int,
                       _ capture: @escaping @MainActor (Int, @escaping @Sendable () -> Void) async throws -> Value)
        -> (gate: ExposureGate, task: Task<Value, Error>) {
        let gate = ExposureGate()
        let task = Task<Value, Error> { @MainActor in
            // A pre-exposure failure must unblock the waiter without enabling lookahead.
            defer { gate.release(exposed: false) }
            return try await capture(index, { Task { @MainActor in gate.release(exposed: true) } })
        }
        tasks.append(task)
        return (gate, task)
    }

    private func cancelPending() {
        stopped = true
        for task in tasks { task.cancel() }
    }
    private func drain() async {
        for task in tasks { _ = try? await task.value }
        tasks.removeAll()
    }
}

@MainActor private final class ExposureGate {
    private var exposed: Bool?
    private var waiter: CheckedContinuation<Bool, Never>?
    func release(exposed value: Bool) {
        guard exposed == nil else { return }
        exposed = value
        waiter?.resume(returning: value)
        waiter = nil
    }
    func wait() async -> Bool {
        if let exposed { return exposed }
        return await withCheckedContinuation { waiter = $0 }
    }
}
