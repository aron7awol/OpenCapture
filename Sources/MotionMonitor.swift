// SPDX-License-Identifier: GPL-3.0-only
import Foundation
import CoreMotion

@MainActor final class MotionMonitor {
    private let manager = CMMotionManager()
    private let continuity = MotionContinuity()
    private var running = false
    private var generation = UUID()
    func start() {
        guard !running else { return }
        let available = manager.isAccelerometerAvailable && manager.isGyroAvailable
        continuity.start(available: available, now: ProcessInfo.processInfo.systemUptime)
        guard available else { return }
        running = true
        let current = UUID(); generation = current
        manager.accelerometerUpdateInterval = 0.02
        manager.gyroUpdateInterval = 0.02
        manager.startAccelerometerUpdates(to: .main) { [weak self] data, error in
            guard let self, self.running, self.generation == current else { return }
            guard let data, error == nil else { self.stop("sensor_error"); return }
            let a = data.acceleration
            self.continuity.sample(sensor: 0, time: data.timestamp,
                x: a.x * 9.80665, y: a.y * 9.80665, z: a.z * 9.80665)
        }
        manager.startGyroUpdates(to: .main) { [weak self] data, error in
            guard let self, self.running, self.generation == current else { return }
            guard let data, error == nil else { self.stop("sensor_error"); return }
            let r = data.rotationRate
            self.continuity.sample(sensor: 1, time: data.timestamp, x: r.x, y: r.y, z: r.z)
        }
    }
    func stop(_ reason: String = "app_inactive") {
        continuity.interrupt(reason); running = false; generation = UUID()
        manager.stopAccelerometerUpdates(); manager.stopGyroUpdates()
    }
    func snapshot() -> MotionSnapshot { continuity.snapshot(now: ProcessInfo.processInfo.systemUptime) }
}
