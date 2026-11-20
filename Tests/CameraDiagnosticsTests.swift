import Foundation
import XCTest
@testable import OpenCaptureCore

final class CameraDiagnosticsTests: XCTestCase {
    func testFirstFaultSurvivesBoundedEventsAndSnapshotIsIndependent() throws {
        let log=CameraDiagnostics(); let request=CaptureRequest()
        log.begin(request)
        log.record("session_runtime_error",["error0Code":"-11800"],fault:true)
        for _ in 0..<100 { log.record("settling_sample") }
        let before=try XCTUnwrap(log.snapshot(request.id))
        XCTAssertEqual(before.events.count,32)
        XCTAssertEqual(before.firstFault?.fields["error0Code"],"-11800")
        log.finish(request.id,error:CaptureError.unavailable("stopped"))
        XCTAssertEqual(before.events.last?.type,"settling_sample")
        XCTAssertEqual(log.snapshot(request.id)?.events.last?.type,"operation_failed")
        XCTAssertEqual(log.snapshot(request.id)?.firstFault?.type,"session_runtime_error")
    }

    func testContextAndErrorUserInfoAreExcludedAndEncodingIsBounded() throws {
        let log=CameraDiagnostics(); var request=CaptureRequest()
        request.context=["secret":"never-export-this"]
        log.begin(request)
        let underlying=NSError(domain:"driver",code:77,userInfo:["password":"never-export-this"])
        let error=NSError(domain:"AVFoundationErrorDomain",code:-11800,userInfo:[NSUnderlyingErrorKey:underlying,
            NSLocalizedDescriptionKey:"never-export-this"])
        log.record("runtime",CameraDiagnostics.errorFields(error),fault:true)
        for _ in 0..<80 {
            log.record("sample",Dictionary(uniqueKeysWithValues:(0..<100).map {
                ("key\($0)",String(repeating:"\"\\\n🙂",count:1000))
            }))
        }
        log.finish(request.id,error:error)
        let snapshot=try XCTUnwrap(log.snapshot(request.id))
        let data=try JSONEncoder().encode(snapshot)
        XCTAssertLessThan(data.count,96*1024)
        XCTAssertFalse(String(decoding:data,as:UTF8.self).contains("never-export-this"))
        XCTAssertEqual(snapshot.firstFault?.fields["error1Domain"],"driver")
        XCTAssertEqual(snapshot.firstFault?.fields["error1Code"],"77")
    }

    func testLateCallbackCannotBecomeAnotherJobsFirstFault() throws {
        let log=CameraDiagnostics();let first=CaptureRequest();let second=CaptureRequest()
        log.begin(first);log.finish(first.id)
        log.begin(second)
        log.record("late_completion",requestID:first.id,fault:true)
        XCTAssertNil(log.snapshot(second.id)?.firstFault)
        XCTAssertFalse(try XCTUnwrap(log.snapshot(second.id)).events.contains { $0.type=="late_completion" })
        XCTAssertEqual(log.snapshot(first.id)?.events.last?.type,"operation_completed")
        XCTAssertNil(log.snapshot("unknown"))
    }

    func testOnlyEightCompletedJobsAreRetainedAndNewJobHasPriorContext() throws {
        let log=CameraDiagnostics();var ids:[String]=[]
        for _ in 0..<10 {
            let request=CaptureRequest();ids.append(request.id)
            log.begin(request);log.record("custom_exposure_completed");log.finish(request.id)
        }
        XCTAssertNil(log.snapshot(ids[0]));XCTAssertNil(log.snapshot(ids[1]))
        XCTAssertNotNil(log.snapshot(ids[2]))
        let last=try XCTUnwrap(log.snapshot(ids[9]))
        XCTAssertLessThanOrEqual(last.priorEvents.count,8)
        XCTAssertTrue(last.priorEvents.allSatisfy { $0.sequence < last.startedSequence })
        XCTAssertEqual(Set(last.events.compactMap(\.requestID)),[ids[9]])
    }

    func testConcurrentNotificationWritesAreOrderedAndBounded() throws {
        let log=CameraDiagnostics();let request=CaptureRequest();log.begin(request)
        DispatchQueue.concurrentPerform(iterations:100) { index in
            log.record("notification",["index":String(index)])
        }
        let events=try XCTUnwrap(log.snapshot(request.id)).events
        XCTAssertEqual(events.count,32)
        XCTAssertEqual(events.map(\.sequence),events.map(\.sequence).sorted())
        XCTAssertEqual(Set(events.map(\.sequence)).count,32)
    }
}
