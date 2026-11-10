import XCTest
@testable import OpenCaptureCore

final class ProtocolTests: XCTestCase {
    func testRequestRoundTrip() throws {
        let request = CaptureRequest()
        XCTAssertEqual(try CaptureRequest.decode(JSONEncoder().encode(request)), request)
    }
    func testProRAWHasDistinctFormatAndQualityContract() throws {
        var request = CaptureRequest()
        request.format = .appleProRAW
        XCTAssertThrowsError(try request.validate())
        request.quality = .quality
        let data = try JSONEncoder().encode(request)
        XCTAssertTrue(String(decoding: data, as: UTF8.self).contains("appleProRaw"))
        XCTAssertEqual(try CaptureRequest.decode(data).format, .appleProRAW)
        request.format = .bayerRAW
        XCTAssertThrowsError(try request.validate())
        request.quality = .speed
        XCTAssertNoThrow(try request.validate())
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

    func testIPOnlyPairingAndExplicitPorts() throws {
        XCTAssertEqual(try EndpointPolicy.controllerURL(" 10.0.0.210 ").absoluteString, "http://10.0.0.210:8765")
        XCTAssertEqual(try EndpointPolicy.controllerURL("192.168.1.2:8877").port, 8877)
        XCTAssertEqual(try EndpointPolicy.controllerURL("http://capture.local").port, 8765)
        XCTAssertEqual(try EndpointPolicy.controllerURL("http://10.0.0.2:8766").port, 8766)
        XCTAssertNil(try EndpointPolicy.controllerURL("https://example.com").port)
        XCTAssertThrowsError(try EndpointPolicy.controllerURL("8.8.8.8"))
        XCTAssertThrowsError(try EndpointPolicy.controllerURL("192.168.1.2:70000"))
        XCTAssertThrowsError(try EndpointPolicy.controllerURL("192.168.1.2/control"))
    }

    func testOptionalPairingTokenIsRestrictedToLocalControllers() throws {
        let local = try EndpointPolicy.controllerURL("10.0.0.2")
        let remote = try EndpointPolicy.controllerURL("https://example.com")
        XCTAssertNoThrow(try EndpointPolicy.validatePairingToken("", for: local))
        XCTAssertThrowsError(try EndpointPolicy.validatePairingToken("", for: remote))
        XCTAssertThrowsError(try EndpointPolicy.validatePairingToken("short", for: local))
        XCTAssertThrowsError(try EndpointPolicy.validatePairingToken(String(repeating: "a", count: 24) + " ", for: local))
        XCTAssertNoThrow(try EndpointPolicy.validatePairingToken(String(repeating: "a", count: 32), for: remote))
    }

    func testApplyControlsAndOldRequests() throws {
        var request = CaptureRequest()
        XCTAssertNil(try CaptureRequest.decode(JSONEncoder().encode(request)).operation)
        request.operation = .applyControls
        XCTAssertThrowsError(try request.validate())
        request.frameCount = 1
        request.exposureSeconds = 0.05
        request.iso = 100
        XCTAssertEqual(try CaptureRequest.decode(JSONEncoder().encode(request)), request)
        var object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(request)) as! [String: Any]
        object["operation"] = "arbitraryCommand"
        XCTAssertThrowsError(try CaptureRequest.decode(JSONSerialization.data(withJSONObject: object)))
    }
}
