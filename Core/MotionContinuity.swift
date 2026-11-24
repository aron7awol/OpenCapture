// SPDX-License-Identifier: GPL-3.0-only
import Foundation

public struct MotionSnapshot: Codable, Equatable {
    public let schemaVersion: Int
    public let instanceID: String
    public let continuityID: String
    public let movementCount: Int
    public let supported: Bool
    public let monitoring: Bool
    public let reason: String
}

/// SI units, with no dependency on capture or network sessions.
public final class MotionContinuity {
    private let lock = NSRecursiveLock()
    private let instance = UUID().uuidString
    private var epoch = UUID().uuidString
    private var count = 0
    private var supported = false
    private var active = false
    private var started = 0.0
    private var last = [-1.0, -1.0]
    private var suspect = [-1.0, -1.0]
    private var baseline: [Double]?
    private var lastMovement = -10.0
    private var reason = "not_started"

    public init() {}
    public func start(available: Bool, now: Double) {
        lock.lock(); defer { lock.unlock() }
        guard !active else { return }
        supported = available; active = available; started = now
        last = [-1, -1]; suspect = [-1, -1]; baseline = nil
        reason = available ? "starting" : "sensors_unavailable"
    }
    public func interrupt(_ why: String) {
        lock.lock(); defer { lock.unlock() }
        epoch = UUID().uuidString; active = false
        last = [-1, -1]; suspect = [-1, -1]; baseline = nil; reason = why
    }
    private func stale(_ now: Double) -> Bool {
        active && now - started > 2 && last.contains { $0 < 0 || now - $0 > 2 }
    }
    public func sample(sensor: Int, time: Double, x: Double, y: Double, z: Double) {
        lock.lock(); defer { lock.unlock() }
        guard active, (0...1).contains(sensor) else { return }
        guard [time, x, y, z].allSatisfy({ $0.isFinite }) else { interrupt("sensor_error"); return }
        if last[sensor] >= 0 && time <= last[sensor] { interrupt("sensor_clock_changed"); return }
        if stale(time) { interrupt("sensor_gap"); start(available: supported, now: time) }
        last[sensor] = time
        let vector = [x, y, z]
        if sensor == 0 && baseline == nil { baseline = vector }
        let magnitude = sensor == 0
            ? sqrt(zip(vector, baseline!).reduce(0) { $0 + pow($1.0 - $1.1, 2) })
            : sqrt(x*x + y*y + z*z)
        let moving = magnitude > (sensor == 0 ? 0.25 : 0.03)
        if !moving { suspect[sensor] = -1 }
        else {
            if suspect[sensor] < 0 { suspect[sensor] = time }
            if time - suspect[sensor] >= 0.12 && time - lastMovement >= 1 {
                count += 1; lastMovement = time; reason = "movement_detected"
                if sensor == 0 { baseline = vector }
            }
        }
    }
    public func snapshot(now: Double) -> MotionSnapshot {
        lock.lock(); defer { lock.unlock() }
        if stale(now) { interrupt("sensor_gap"); start(available: supported, now: now) }
        let ready = active && last.allSatisfy { $0 >= 0 && now - $0 <= 2 } && now - started >= 0.5
        return MotionSnapshot(schemaVersion: 1, instanceID: instance, continuityID: epoch,
            movementCount: count, supported: supported, monitoring: ready, reason: reason)
    }
}
