import XCTest
import UIKit

final class NavigationTests: XCTestCase {
    func testRemoteReadyAndRetentionSettings() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        addUIInterruptionMonitor(withDescription: "Camera permission") { alert in
            if alert.buttons["Allow"].exists { alert.buttons["Allow"].tap(); return true }
            if alert.buttons["OK"].exists { alert.buttons["OK"].tap(); return true }
            return false
        }
        app.launch(); app.tap()
        if app.alerts["OpenCapture"].waitForExistence(timeout: 10) { app.alerts["OpenCapture"].buttons["OK"].tap() }
        app.tabBars.buttons["Captures"].tap()
        XCTAssertEqual(app.switches["autoDeleteRemote"].value as? String, "1")
        XCTAssertTrue(app.buttons["Delete all captures"].exists)
        app.tabBars.buttons["Connection"].tap()
        let address = app.textFields["controllerAddress"]
        address.tap(); address.typeText("127.0.0.1:9")
        if app.buttons["Done"].exists { app.buttons["Done"].tap() }
        let enter = app.buttons["enterRemoteReady"]
        for _ in 0..<4 { if enter.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(enter.isHittable); enter.tap()
        XCTAssertTrue(app.staticTexts["remoteReadyScreen"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.tabBars.firstMatch.isHittable)
        attach("remote-ready-portrait", app)
        app.buttons["showRemoteControls"].tap()
        XCTAssertTrue(app.buttons["dimRemoteScreen"].waitForExistence(timeout: 3))
        app.buttons["dimRemoteScreen"].tap()
        XCTAssertTrue(app.staticTexts["remoteReadyScreen"].waitForExistence(timeout: 3))
        app.buttons["endRemoteReady"].tap()
        XCTAssertTrue(app.buttons["enterRemoteReady"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.textFields["controllerAddress"].isEnabled)
    }

    func testCameraGalleryAndConnection() throws {
        continueAfterFailure = false
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication()
        addUIInterruptionMonitor(withDescription: "Camera permission") { alert in
            if alert.buttons["Allow"].exists { alert.buttons["Allow"].tap(); return true }
            if alert.buttons["OK"].exists { alert.buttons["OK"].tap(); return true }
            return false
        }
        app.launch()
        // Interruption monitors run on interaction, not on existence queries.
        app.tap()
        if app.alerts["OpenCapture"].waitForExistence(timeout: 10) { app.alerts["OpenCapture"].buttons["OK"].tap() }
        XCTAssertTrue(app.navigationBars["OpenCapture"].waitForExistence(timeout: 5))
        assertPreviewFits(app)
        let mode = app.buttons["captureMode"]
        XCTAssertTrue(mode.exists)
        // A row underneath the capture inset can still report isHittable.
        // Bring the entire picker above that inset before opening its menu.
        if !mode.isHittable || mode.frame.maxY > app.buttons["captureButton"].frame.minY { app.swipeUp() }
        mode.tap()
        let fastJPEG = app.buttons["Fast JPEG burst"]
        XCTAssertTrue(fastJPEG.waitForExistence(timeout: 5))
        fastJPEG.tap()
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
        let preview = app.otherElements.matching(identifier: "cameraPreview").firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        // Query concrete elements after XCTest's layout/gesture idle wait. Broad
        // descendant queries inside a five-second predicate can exhaust its
        // timeout while resolving SwiftUI's accessibility tree on a cold runner.
        let bounds = app.windows.firstMatch.frame
        let frame = preview.frame
        let capture = app.buttons["captureButton"].frame
        let navigation = app.navigationBars["OpenCapture"].frame
        XCTAssertGreaterThan(frame.width, 0)
        XCTAssertGreaterThan(frame.height, 0)
        XCTAssertTrue(bounds.contains(frame), "Preview \(frame) exceeds window \(bounds)")
        XCTAssertGreaterThanOrEqual(frame.minY, navigation.maxY - 1)
        XCTAssertLessThanOrEqual(frame.maxY, capture.minY + 1)
    }

    private func waitForLayout(_ app: XCUIApplication, title: String, landscape: Bool) {
        let layout = expectation(for: NSPredicate { _, _ in
            let window = app.windows.firstMatch.frame
            let navigation = app.navigationBars[title].frame
            let rotated = landscape ? window.width > window.height : window.height > window.width
            return rotated && abs(navigation.width - window.width) <= 1 &&
                navigation.maxX <= window.maxX && navigation.minY >= window.minY
        }, evaluatedWith: app)
        wait(for: [layout], timeout: 10)
        // Accessibility reports destination frames before the rotation's
        // compositor animation finishes. Let it settle before swiping or
        // capturing the screen, especially between opposite landscape sides.
        Thread.sleep(forTimeInterval: 1)
    }

    private func attach(_ name: String, _ app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
