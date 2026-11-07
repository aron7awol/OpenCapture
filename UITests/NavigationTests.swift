import XCTest

final class NavigationTests: XCTestCase {
    func testCameraGalleryAndConnection() throws {
        let app = XCUIApplication()
        app.launch()
        addUIInterruptionMonitor(withDescription: "Camera permission") { alert in
            if alert.buttons["Allow"].exists { alert.buttons["Allow"].tap(); return true }
            if alert.buttons["OK"].exists { alert.buttons["OK"].tap(); return true }
            return false
        }
        if app.alerts["OpenCapture"].waitForExistence(timeout: 10) { app.alerts["OpenCapture"].buttons["OK"].tap() }
        XCTAssertTrue(app.navigationBars["OpenCapture"].waitForExistence(timeout: 5))
        let mode = app.buttons["captureMode"]
        XCTAssertTrue(mode.exists)
        mode.tap()
        app.buttons["Fast JPEG burst"].tap()
        attach("camera-portrait", app)
        app.tabBars.buttons["Captures"].tap()
        XCTAssertTrue(app.staticTexts["No captures"].waitForExistence(timeout: 3))
        attach("empty-capture-gallery", app)
        app.tabBars.buttons["Connection"].tap()
        XCTAssertTrue(app.buttons["Check for updates"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.secureTextFields["Pairing token"].exists)
        attach("connection-portrait", app)
        XCUIDevice.shared.orientation = .landscapeLeft
        attach("connection-landscape", app)
        XCUIDevice.shared.orientation = .portrait
    }

    private func attach(_ name: String, _ app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
