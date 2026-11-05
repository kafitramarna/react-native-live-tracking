import Foundation

/**
 * Persists minimal tracking state to UserDefaults so a tracking session can be
 * resumed natively after process death — iOS counterpart of the Android
 * TrackingStateStore (SharedPreferences).
 *
 * One-shot markers (lastStopReason / lastRestartReason) are consumed on read so
 * each anomaly is reported exactly once to the JS layer.
 */
final class TrackingStateStore {

    private static let keyIsTrackingActive = "is_tracking_active"
    private static let keyConfigJson = "config_json"
    private static let keyLastStopReason = "last_stop_reason"
    private static let keyLastRestartReason = "last_restart_reason"

    private static var defaults: UserDefaults {
        UserDefaults.standard
    }

    // MARK: - Tracking Active Flag

    static func saveTrackingActive(_ active: Bool) {
        defaults.set(active, forKey: keyIsTrackingActive)
    }

    static func isTrackingActive() -> Bool {
        defaults.bool(forKey: keyIsTrackingActive)
    }

    // MARK: - Persisted Config

    static func saveConfig(_ configJson: String) {
        defaults.set(configJson, forKey: keyConfigJson)
    }

    static func loadConfig() -> String? {
        defaults.string(forKey: keyConfigJson)
    }

    // MARK: - One-shot Markers

    static func saveLastStopReason(_ reason: String) {
        defaults.set(reason, forKey: keyLastStopReason)
    }

    static func consumeLastStopReason() -> String? {
        let value = defaults.string(forKey: keyLastStopReason)
        defaults.removeObject(forKey: keyLastStopReason)
        return value
    }

    static func saveLastRestartReason(_ reason: String) {
        defaults.set(reason, forKey: keyLastRestartReason)
    }

    static func consumeLastRestartReason() -> String? {
        let value = defaults.string(forKey: keyLastRestartReason)
        defaults.removeObject(forKey: keyLastRestartReason)
        return value
    }
}
