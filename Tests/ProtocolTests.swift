import XCTest
@testable import OpenCaptureCore

final class ProtocolTests: XCTestCase {
    func testRequestRoundTrip() throws {
        let request = CaptureRequest()
        XCTAssertEqual(try CaptureRequest.decode(JSONEncoder().encode(request)), request)
    }
    func testRejectsInvalidSettings() {
        var request = CaptureRequest()
        request.frameCount = 0
        XCTAssertThrowsError(try request.validate())
        request.frameCount = 33
        XCTAssertThrowsError(try request.validate())
        request.frameCount = 32
        request.exposureSeconds = 0.02
        XCTAssertThrowsError(try request.validate())
        request.iso = 100
        XCTAssertNoThrow(try request.validate())
        request.focusPosition = .nan
        XCTAssertThrowsError(try request.validate())
    }
    func testUnknownFieldsAndVersionsFail() throws {
        var object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(CaptureRequest())) as! [String: Any]
        object["calibrate"] = true
        XCTAssertThrowsError(try CaptureRequest.decode(JSONSerialization.data(withJSONObject: object)))
        object.removeValue(forKey: "calibrate")
        object["protocolVersion"] = 2
        XCTAssertThrowsError(try CaptureRequest.decode(JSONSerialization.data(withJSONObject: object)))
    }
    func testControllerURLPolicy() {
        for text in ["http://10.0.0.2:8766", "http://192.168.1.2:8766", "http://capture.local:8766", "https://example.com"] {
            XCTAssertNoThrow(try EndpointPolicy.controllerURL(text))
        }
        for text in ["http://example.com", "http://10.0.0.999", "https://user:pass@example.com", "http://127.0.0.1/?token=x", "file:///tmp/test", "http://192.168.1.1/api"] {
            XCTAssertThrowsError(try EndpointPolicy.controllerURL(text))
        }
    }
    func testNumericVersions() {
        XCTAssertTrue(ReleaseVersion.newer(version: "0.10.0", build: "1", than: "0.9.0", build: "99"))
        XCTAssertTrue(ReleaseVersion.newer(version: "0.1.0", build: "12", than: "0.1.0", build: "9"))
        XCTAssertFalse(ReleaseVersion.newer(version: "0.1.0", build: "1", than: "0.2.0", build: "1"))
    }
}
