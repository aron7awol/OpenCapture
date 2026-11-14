import Foundation
import XCTest
@testable import OpenCaptureCore

@MainActor final class ExposurePipelineTests: XCTestCase {
    private enum Failure: Error { case camera }
    @MainActor private final class Camera {
        var started: [Int] = []
        var exposes: [Int: @Sendable () -> Void] = [:]
        var writers: [Int: CheckedContinuation<Int, Error>] = [:]
        var maximumUnfinished = 0
        func photo(_ index: Int, _ exposed: @escaping @Sendable () -> Void) async throws -> Int {
            started.append(index); exposes[index] = exposed
            return try await withCheckedThrowingContinuation { continuation in
                writers[index] = continuation
                maximumUnfinished = max(maximumUnfinished, writers.count)
            }
        }
        func finish(_ index: Int, error: Error? = nil) {
            let writer = writers.removeValue(forKey: index)!
            if let error { writer.resume(throwing: error) } else { writer.resume(returning: index) }
        }
    }
    private func wait(_ condition: () -> Bool) async throws {
        for _ in 0..<200 {
            if condition() { return }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTFail("Pipeline condition timed out")
        throw Failure.camera
    }

    func testExposureOverlapHasTwoWritersAndOrderedConsumption() async throws {
        let camera = Camera(); var consumed: [Int] = []
        let pipeline = ExposurePipeline<Int>()
        let run = Task { @MainActor in
            try await pipeline.run(limit: 3, overlap: true, reserve: { _ in true }, finished: { false },
                capture: { try await camera.photo($0, $1) }, consume: { index, value in
                    XCTAssertEqual(index, value); consumed.append(value); return true
                })
        }
        try await wait { camera.started == [1] }
        camera.exposes[1]!()
        try await wait { camera.started == [1, 2] }
        XCTAssertTrue(consumed.isEmpty) // Next exposure precedes the first file's completion.
        camera.exposes[2]!()
        camera.finish(2) // A later file may finish first without being consumed first.
        try await Task.sleep(nanoseconds: 10_000_000)
        XCTAssertEqual(camera.started, [1, 2])
        camera.finish(1)
        try await wait { camera.started == [1, 2, 3] }
        camera.exposes[3]!(); camera.finish(3)
        try await run.value
        XCTAssertEqual(consumed, [1, 2, 3])
        XCTAssertEqual(camera.maximumUnfinished, 2)
        XCTAssertTrue(camera.writers.isEmpty)
    }

    func testAdmissionCannotBlockPublicationOfTheCurrentFrame() async throws {
        let camera = Camera(); var consumed: [Int] = []; var opportunisticChecks = 0
        let pipeline = ExposurePipeline<Int>()
        let run = Task { @MainActor in
            try await pipeline.run(limit: 2, overlap: true, reserve: { wait in
                if !wait { opportunisticChecks += 1; return false }
                return true
            }, finished: { false }, capture: { try await camera.photo($0, $1) }, consume: { _, value in
                consumed.append(value); return true
            })
        }
        try await wait { camera.started == [1] }
        camera.exposes[1]!()
        try await wait { opportunisticChecks == 1 }
        XCTAssertEqual(camera.started, [1])
        camera.finish(1)
        try await wait { camera.started == [1, 2] }
        XCTAssertEqual(consumed, [1])
        camera.finish(2)
        try await run.value
        XCTAssertEqual(consumed, [1, 2])
    }

    func testFailureBeforeExposureDoesNotLaunchLookahead() async {
        var started: [Int] = []
        do {
            try await ExposurePipeline<Int>().run(limit: 6, overlap: true, reserve: { _ in true }, finished: { false },
                capture: { index, _ in started.append(index); throw Failure.camera }, consume: { _, _ in
                    XCTFail("Failed file was consumed"); return true
                })
            XCTFail("Expected camera failure")
        } catch {}
        XCTAssertEqual(started, [1])
    }

    func testFrozenResultDrainsAndIgnoresOnlyTheUnselectedFailure() async throws {
        let camera = Camera(); var frozen = false; var returned = false; var consumed: [Int] = []
        let pipeline = ExposurePipeline<Int>()
        let run = Task { @MainActor in
            try await pipeline.run(limit: 2, overlap: true, reserve: { _ in true }, finished: { frozen },
                capture: { try await camera.photo($0, $1) }, consume: { _, value in
                    consumed.append(value); frozen = true; return false
                })
            returned = true
        }
        try await wait { camera.started == [1] }
        camera.exposes[1]!()
        try await wait { camera.started == [1, 2] }
        camera.finish(1)
        try await wait { frozen }
        XCTAssertFalse(returned)
        camera.finish(2, error: Failure.camera)
        try await run.value
        XCTAssertTrue(returned); XCTAssertEqual(consumed, [1]); XCTAssertTrue(camera.writers.isEmpty)
    }

    func testCancellationWaitsForAnAdmittedWriter() async throws {
        let camera = Camera(); var returned = false
        let pipeline = ExposurePipeline<Int>()
        let run = Task { @MainActor in
            defer { returned = true }
            try await pipeline.run(limit: 6, overlap: true, reserve: { _ in true }, finished: { false },
                capture: { try await camera.photo($0, $1) }, consume: { _, _ in true })
        }
        try await wait { camera.started == [1] }
        camera.exposes[1]!()
        try await wait { camera.started == [1, 2] }
        run.cancel()
        camera.finish(1)
        try await Task.sleep(nanoseconds: 10_000_000)
        XCTAssertFalse(returned)
        XCTAssertEqual(camera.started, [1, 2])
        camera.finish(2)
        do { try await run.value; XCTFail("Expected cancellation") }
        catch is CancellationError {} catch { XCTFail("Unexpected error: \(error)") }
        XCTAssertTrue(camera.writers.isEmpty)
    }

    func testSequentialModeKeepsOnlyOneWriter() async throws {
        let camera = Camera(); let pipeline = ExposurePipeline<Int>()
        let run = Task { @MainActor in
            try await pipeline.run(limit: 2, overlap: false, reserve: { _ in true }, finished: { false },
                capture: { try await camera.photo($0, $1) }, consume: { _, _ in true })
        }
        try await wait { camera.started == [1] }
        camera.exposes[1]!()
        try await Task.sleep(nanoseconds: 10_000_000)
        XCTAssertEqual(camera.started, [1])
        camera.finish(1)
        try await wait { camera.started == [1, 2] }
        camera.finish(2)
        try await run.value
        XCTAssertEqual(camera.maximumUnfinished, 1)
    }
}
