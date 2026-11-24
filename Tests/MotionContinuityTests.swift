import XCTest
@testable import OpenCaptureCore

final class MotionContinuityTests: XCTestCase {
    private func feed(_ m: MotionContinuity, _ from: Int, _ to: Int, x: Double = 0, gyro: Double = 0) {
        for i in from...to {
            let t = Double(i) * 0.02
            m.sample(sensor: 0, time: t, x: x, y: 0, z: 9.80665)
            m.sample(sensor: 1, time: t, x: 0, y: 0, z: gyro)
        }
    }
    func testStationaryAndSmallVibrationsKeepContinuity() {
        let m = MotionContinuity(); m.start(available: true, now: 0); feed(m, 0, 40)
        let a = m.snapshot(now: 0.8)
        XCTAssertTrue(a.monitoring)
        feed(m, 41, 100, x: 0.05, gyro: 0.005)
        let b = m.snapshot(now: 2)
        XCTAssertEqual(a.continuityID, b.continuityID)
        XCTAssertEqual(b.movementCount, 0)
    }
    func testMotionIsLatchedButOneSpikeIsNot() {
        for rotation in [false, true] {
            let m = MotionContinuity(); m.start(available: true, now: 0); feed(m, 0, 30)
            feed(m, 31, 31, x: 1, gyro: 0.2); feed(m, 32, 40)
            XCTAssertEqual(m.snapshot(now: 0.8).movementCount, 0)
            feed(m, 41, 60, x: rotation ? 0 : 0.5, gyro: rotation ? 0.1 : 0)
            let count = m.snapshot(now: 1.2).movementCount
            XCTAssertGreaterThan(count, 0)
            feed(m, 61, 80, x: rotation ? 0 : 0.5)
            XCTAssertEqual(m.snapshot(now: 1.6).movementCount, count)
        }
    }
    func testBackgroundAndMissingSensorChangeContinuity() {
        let m = MotionContinuity(); m.start(available: true, now: 0); feed(m, 0, 30)
        let a = m.snapshot(now: 0.6)
        m.interrupt("app_inactive"); m.start(available: true, now: 1); feed(m, 50, 80)
        let b = m.snapshot(now: 1.6)
        XCTAssertNotEqual(a.continuityID, b.continuityID)
        for i in 81...200 { m.sample(sensor: 0, time: Double(i)*0.02, x: 0, y: 0, z: 9.80665) }
        let c = m.snapshot(now: 4)
        XCTAssertNotEqual(b.continuityID, c.continuityID)
        XCTAssertFalse(c.monitoring)
    }
    func testUnavailableAndRestart() {
        let m = MotionContinuity(); m.start(available: false, now: 0)
        XCTAssertFalse(m.snapshot(now: 1).supported)
        XCTAssertFalse(m.snapshot(now: 1).monitoring)
        XCTAssertNotEqual(m.snapshot(now: 1).instanceID, MotionContinuity().snapshot(now: 1).instanceID)
    }
}
