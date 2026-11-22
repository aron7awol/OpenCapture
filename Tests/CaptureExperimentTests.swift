import XCTest
@testable import OpenCaptureCore

final class CaptureExperimentTests: XCTestCase {
    private func request() -> CaptureRequest {
        var value = CaptureRequest()
        value.exposureSeconds = 1; value.iso = 100; value.focusPosition = 0.5
        value.whiteBalance = WhiteBalance(red:2,green:1,blue:1.5)
        value.includePreview = false
        return value
    }
    func testEveryModeRoundTripsAndStandardRemainsAbsent() throws {
        XCTAssertNil(try CaptureRequest.decode(JSONEncoder().encode(request())).captureExperiment)
        for mode in CaptureExperiment.allCases {
            var value = request(); value.captureExperiment = mode
            XCTAssertEqual(try CaptureRequest.decode(JSONEncoder().encode(value)),value)
        }
    }
    func testExperimentsRejectAutomaticPartialProcessedAndControlOnlyRequests() {
        for mode in CaptureExperiment.allCases {
            var base = request(); base.captureExperiment = mode
            for change: (inout CaptureRequest) -> Void in [
                { $0.exposureSeconds = nil; $0.iso = nil }, { $0.focusPosition = nil },
                { $0.whiteBalance = nil }, { $0.includePreview = true }, { $0.includePreview = nil },
                { $0.format = .jpeg }, { $0.format = .appleProRAW; $0.quality = .quality },
                { $0.averaging = AveragingOptions(mode:"bayer-mean-v2",emptyFramePolicy:"relative",keepOriginals:true) },
                { $0.operation = .applyControls; $0.frameCount = 1 }
            ] {
                var value = base; change(&value)
                XCTAssertThrowsError(try value.validate())
            }
        }
    }
    func testUnknownExperimentIsNotSilentlyIgnored() throws {
        var object = try JSONSerialization.jsonObject(with:JSONEncoder().encode(request())) as! [String:Any]
        object["captureExperiment"] = "ios-unknown"
        XCTAssertThrowsError(try CaptureRequest.decode(JSONSerialization.data(withJSONObject:object)))
    }
    func testOutOfOrderBracketCallbacksAssociateBySequence() throws {
        var identity = BracketCaptureIdentity(requestID:7,count:2)
        try identity.accept(requestID:7,sequence:2,photoCount:2,expectedCount:2,timestamp:102,raw:true)
        XCTAssertFalse(identity.complete)
        try identity.accept(requestID:7,sequence:1,photoCount:1,expectedCount:2,timestamp:101,raw:true)
        XCTAssertTrue(identity.complete)
        XCTAssertThrowsError(try identity.accept(requestID:7,sequence:1,photoCount:1,expectedCount:2,timestamp:101,raw:true))
    }
    func testStaleMalformedAndReusedTimestampsAreRejected() throws {
        var identity = BracketCaptureIdentity(requestID:7,count:2)
        XCTAssertThrowsError(try identity.accept(requestID:6,sequence:1,photoCount:1,expectedCount:2,timestamp:101,raw:true))
        XCTAssertThrowsError(try identity.accept(requestID:7,sequence:0,photoCount:1,expectedCount:2,timestamp:101,raw:true))
        XCTAssertThrowsError(try identity.accept(requestID:7,sequence:1,photoCount:2,expectedCount:2,timestamp:101,raw:true))
        XCTAssertThrowsError(try identity.accept(requestID:7,sequence:1,photoCount:1,expectedCount:4,timestamp:101,raw:true))
        XCTAssertThrowsError(try identity.accept(requestID:7,sequence:1,photoCount:1,expectedCount:2,timestamp:101,raw:false))
        XCTAssertThrowsError(try identity.accept(requestID:7,sequence:1,photoCount:1,expectedCount:2,timestamp:.nan,raw:true))
        try identity.accept(requestID:7,sequence:1,photoCount:1,expectedCount:2,timestamp:101,raw:true)
        XCTAssertThrowsError(try identity.accept(requestID:7,sequence:2,photoCount:2,expectedCount:2,timestamp:101,raw:true))
        XCTAssertThrowsError(try identity.accept(requestID:7,sequence:2,photoCount:2,expectedCount:2,timestamp:100,raw:true))
    }
}

@MainActor final class BracketCapturePipelineTests: XCTestCase {
    enum Failure: Error { case camera, consumer }
    func testPairsAndOddTailReserveBeforeCapture() async throws {
        var permits = 0, consumed: [Int] = [], batches: [[Int]] = []
        try await BracketCapturePipeline<Int>().run(limit:5,reserve:{ _ in permits += 1; return true },finished:{ false },capture:{ indices in
            XCTAssertEqual(permits-consumed.count,indices.count)
            XCTAssertLessThanOrEqual(indices.count,2)
            batches.append(indices); return indices
        },consume:{ index,value in XCTAssertEqual(index,value); consumed.append(value) })
        XCTAssertEqual(batches,[[1,2],[3,4],[5]])
        XCTAssertEqual(consumed,[1,2,3,4,5])
    }
    func testOnePermitDoesNotDeadlockWaitingForSecond() async throws {
        var batches: [[Int]] = []
        try await BracketCapturePipeline<Int>().run(limit:3,reserve:{ wait in wait },finished:{ false },capture:{ indices in
            batches.append(indices); return indices
        },consume:{ _,_ in })
        XCTAssertEqual(batches,[[1],[2],[3]])
    }
    func testFreezePreventsFurtherCaptureAndPublication() async throws {
        var frozen = false, captured = 0, consumed = 0
        try await BracketCapturePipeline<Int>().run(limit:12,reserve:{ _ in true },finished:{ frozen },capture:{ indices in
            captured += indices.count; return indices
        },consume:{ _,_ in consumed += 1; frozen = true })
        XCTAssertEqual(captured,2); XCTAssertEqual(consumed,1)
    }
    func testFailureDoesNotAdmitAnotherBatch() async {
        var captures = 0
        do {
            try await BracketCapturePipeline<Int>().run(limit:6,reserve:{ _ in true },finished:{ false },capture:{ _ in
                captures += 1; throw Failure.camera
            },consume:{ _,_ in XCTFail("Failed capture published") })
            XCTFail("Expected failure")
        } catch {}
        XCTAssertEqual(captures,1)
    }
    func testIncompleteBatchAndConsumerFailureAreErrors() async {
        for short in [true,false] {
            var calls = 0
            do {
                try await BracketCapturePipeline<Int>().run(limit:6,reserve:{ _ in true },finished:{ false },capture:{ indices in
                    calls += 1; return short ? [indices[0]] : indices
                },consume:{ _,_ in throw Failure.consumer })
                XCTFail("Expected failure")
            } catch {}
            XCTAssertEqual(calls,1)
        }
    }
    func testCancellationDrainsAdmittedWriter() async throws {
        var entered = false, drained = false
        let task = Task {
            try await BracketCapturePipeline<Int>().run(limit:6,reserve:{ _ in true },finished:{ false },capture:{ indices in
                entered = true
                do { try await Task.sleep(nanoseconds:10_000_000_000) }
                catch { drained = true; throw error }
                return indices
            },consume:{ _,_ in XCTFail("Cancelled capture published") })
        }
        for _ in 0..<100 where !entered { try await Task.sleep(nanoseconds:1_000_000) }
        XCTAssertTrue(entered)
        task.cancel()
        do { try await task.value; XCTFail("Expected cancellation") } catch {}
        XCTAssertTrue(drained)
    }
}
