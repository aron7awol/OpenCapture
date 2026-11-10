import XCTest
import UIKit

final class NavigationTests: XCTestCase {
    func testCameraGalleryAndConnection() throws {
        continueAfterFailure = false
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication()
        app.launch()
        addUIInterruptionMonitor(withDescription: "Camera permission") { alert in
            if alert.buttons["Allow"].exists { alert.buttons["Allow"].tap(); return true }
            if alert.buttons["OK"].exists { alert.buttons["OK"].tap(); return true }
            return false
        }
        if app.alerts["OpenCapture"].waitForExistence(timeout: 10) { app.alerts["OpenCapture"].buttons["OK"].tap() }
        XCTAssertTrue(app.navigationBars["OpenCapture"].waitForExistence(timeout: 5))
        assertPreviewFits(app)
        let mode = app.buttons["captureMode"]
        XCTAssertTrue(mode.exists)
        if !mode.isHittable { app.swipeUp() }
        mode.tap()
        app.buttons["Fast JPEG burst"].tap()
        app.swipeDown()
        attach("camera-portrait", app)
        for (orientation, name) in [(UIDeviceOrientation.landscapeLeft, "camera-landscape-left"), (.landscapeRight, "camera-landscape-right")] {
            XCUIDevice.shared.orientation = orientation
            waitForLayout(app, title: "OpenCapture", landscape: true)
            app.swipeDown()
            assertPreviewFits(app)
            let capture = app.buttons["captureButton"]
            XCTAssertTrue(capture.exists)
            XCTAssertTrue(app.windows.firstMatch.frame.contains(capture.frame))
            attach(name, app)
            // Pass through portrait so the next landscape transition must finish.
            XCUIDevice.shared.orientation = .portrait
            waitForLayout(app, title: "OpenCapture", landscape: false)
        }
        app.tabBars.buttons["Captures"].tap()
        XCTAssertTrue(app.staticTexts["No captures"].waitForExistence(timeout: 3))
        attach("empty-capture-gallery", app)
        app.tabBars.buttons["Connection"].tap()
        XCTAssertTrue(app.buttons["Check for updates"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.textFields["controllerAddress"].exists)
        XCTAssertFalse(app.secureTextFields["Pairing token (if required)"].exists)
        attach("connection-portrait", app)
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForLayout(app, title: "Connection", landscape: true)
        XCTAssertTrue(app.textFields["controllerAddress"].isHittable)
        attach("connection-landscape", app)
        XCUIDevice.shared.orientation = .portrait
    }

    private func assertPreviewFits(_ app: XCUIApplication) {
        let preview = app.descendants(matching: .any)["cameraPreview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        let visible = expectation(for: NSPredicate { _, _ in
            let bounds = app.windows.firstMatch.frame
            let frame = preview.frame
            let capture = app.buttons["captureButton"].frame
            return frame.width > 0 && frame.height > 0 && bounds.contains(frame)
                && frame.minY >= app.navigationBars["OpenCapture"].frame.maxY
                && frame.maxY <= capture.minY
        }, evaluatedWith: app)
        wait(for: [visible], timeout: 5)
    }

    private func waitForLayout(_ app: XCUIApplication, title: String, landscape: Bool) {
        let layout = expectation(for: NSPredicate { _, _ in
            let window = app.windows.firstMatch.frame
            let navigation = app.navigationBars[title].frame
            let rotated = landscape ? window.width > window.height : window.height > window.width
            return rotated && navigation.maxX <= window.maxX && navigation.minY >= window.minY
        }, evaluatedWith: app)
        wait(for: [layout], timeout: 10)
    }

    private func attach(_ name: String, _ app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
