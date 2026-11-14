import XCTest
@testable import OpenCaptureCore

final class CaptureOptimizationTests: XCTestCase {
    func testControlReuseRequiresAllManualValuesAndExactIntent() throws {
        var request=CaptureRequest()
        XCTAssertNil(CaptureControlKey(request))
        request.exposureSeconds=1.14; request.iso=100; request.focusPosition=0
        request.whiteBalance=WhiteBalance(red:2,green:1,blue:1.5)
        let key=try XCTUnwrap(CaptureControlKey(request))
        var next=request; next.id=UUID().uuidString.lowercased(); next.frameCount=18
        next.context=["different":"pattern"]; next.includePreview=false
        XCTAssertEqual(key,CaptureControlKey(next))
        next.iso=101
        XCTAssertNotEqual(key,CaptureControlKey(next))
        XCTAssertTrue(key.honors(exposure:1.14,iso:100,focus:0,whiteBalance:request.whiteBalance!))
        XCTAssertFalse(key.honors(exposure:0.5,iso:100,focus:0,whiteBalance:request.whiteBalance!))
    }

    @MainActor func testSingleInspectionAndCooperativeDrain() async throws {
        let processor=FrameProcessor<Int>()
        try processor.submit { 7 }
        XCTAssertTrue(processor.hasPending)
        XCTAssertThrowsError(try processor.submit { 8 })
        let value=try await processor.take()
        XCTAssertEqual(value,7)
        XCTAssertFalse(processor.hasPending)
        try processor.submit {
            while true { try Task.checkCancellation(); Thread.sleep(forTimeInterval:0.001) }
        }
        await processor.cancelAndWait()
        XCTAssertFalse(processor.hasPending)
    }

    func testOptionalPreviewRoundTripAndInvalidType() throws {
        var request=CaptureRequest(); request.includePreview=false
        XCTAssertEqual(try CaptureRequest.decode(JSONEncoder().encode(request)),request)
        var object=try JSONSerialization.jsonObject(with:JSONEncoder().encode(request)) as! [String:Any]
        object["includePreview"]="false"
        XCTAssertThrowsError(try CaptureRequest.decode(JSONSerialization.data(withJSONObject:object)))
    }
}
