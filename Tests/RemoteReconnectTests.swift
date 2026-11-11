import Foundation
import XCTest
@testable import OpenCaptureCore

final class RemoteReconnectTests: XCTestCase {
    func testOfflineAndServerFailuresRetryButIdentityAndProtocolFailuresDoNot() {
        for status in [408, 425, 429, 500, 503] { XCTAssertTrue(RemoteReconnect.retryable(ControllerHTTPError(status: status))) }
        for status in [301, 302, 400, 401, 403, 404, 409] { XCTAssertFalse(RemoteReconnect.retryable(ControllerHTTPError(status: status))) }
        for code in [URLError.Code.timedOut, .networkConnectionLost, .notConnectedToInternet, .cannotConnectToHost] {
            XCTAssertTrue(RemoteReconnect.retryable(URLError(code)))
        }
        for code in [URLError.Code.cancelled, .serverCertificateUntrusted, .secureConnectionFailed, .badURL] {
            XCTAssertFalse(RemoteReconnect.retryable(URLError(code)))
        }
        XCTAssertFalse(RemoteReconnect.retryable(CaptureError.transport("Malformed lease")))
        XCTAssertFalse(RemoteReconnect.retryable(CancellationError()))
    }
    func testBackoffIsBounded() {
        XCTAssertEqual((0...6).map { RemoteReconnect.delaySeconds(failure: $0) }, [1, 2, 4, 8, 15, 15, 15])
    }
}
