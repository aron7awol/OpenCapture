import XCTest
import UIKit

final class NavigationTests: XCTestCase {
    func testMinimalCameraScreenBothOrientationsAndAbout() throws {
        continueAfterFailure = false
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication()
        addUIInterruptionMonitor(withDescription: "Permissions") { alert in
            for name in ["Allow", "OK"] where alert.buttons[name].exists { alert.buttons[name].tap(); return true }
            return false
        }
        XCUIDevice.shared.orientation = .portrait
        app.launch(); app.tap()
        XCTAssertTrue(app.buttons["aboutButton"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.tabBars.firstMatch.exists)
        XCTAssertFalse(app.textFields.firstMatch.exists)
        XCTAssertFalse(app.buttons["captureButton"].exists)
        assertFits(app, landscape: false)
        attach("camera-portrait", app)
        for (orientation, name) in [(UIDeviceOrientation.landscapeLeft, "camera-landscape-left"), (.landscapeRight, "camera-landscape-right")] {
            XCUIDevice.shared.orientation = orientation
            let rotated = expectation(for: NSPredicate { _, _ in
                let bounds = app.windows.firstMatch.frame
                return bounds.width > bounds.height && app.buttons["aboutButton"].frame.minX > app.otherElements["cameraPreview"].frame.maxX - 1
            }, evaluatedWith: app)
            wait(for: [rotated], timeout: 10)
            assertFits(app, landscape: true); attach(name, app)
            XCUIDevice.shared.orientation = .portrait
            let portrait = expectation(for: NSPredicate { _, _ in
                let bounds = app.windows.firstMatch.frame
                return bounds.height > bounds.width
            }, evaluatedWith: app)
            wait(for: [portrait], timeout: 10)
        }
        app.buttons["aboutButton"].tap()
        XCTAssertTrue(app.staticTexts["Version"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Check for updates"].exists)
        attach("about", app)
        app.buttons["Done"].tap()
        app.buttons["serverToggle"].tap()
        XCTAssertTrue(app.buttons["Resume camera server"].waitForExistence(timeout: 5))
    }
    private func assertFits(_ app: XCUIApplication, landscape: Bool) {
        let preview = app.otherElements["cameraPreview"]
        XCTAssertTrue(preview.exists)
        let bounds = app.windows.firstMatch.frame
        XCTAssertTrue(bounds.contains(preview.frame))
        XCTAssertGreaterThan(preview.frame.width, 0); XCTAssertGreaterThan(preview.frame.height, 0)
        for id in ["aboutButton", "serverToggle"] {
            let button = app.buttons[id]
            XCTAssertTrue(button.isHittable); XCTAssertTrue(bounds.contains(button.frame))
            if landscape { XCTAssertGreaterThanOrEqual(button.frame.minX, preview.frame.maxX - 1) }
            else { XCTAssertGreaterThanOrEqual(button.frame.minY, preview.frame.maxY - 1) }
        }
    }
    private func attach(_ name: String, _ app: XCUIApplication) {
        // Element screenshots can crop a rotated simulator in portrait coordinates.
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}
