import Foundation
import CoreMotion

/**
 * Activity types recognized by the handler.
 */
enum ActivityType {
    case stationary
    case walking
    case automotive
    case unknown
}

/**
 * Protocol for receiving activity state changes from ActivityRecognitionHandler.
 */
protocol ActivityStateDelegate: AnyObject {
    /**
     * Called when the detected activity type changes.
     *
     * - Parameter activity: The new detected activity type
     */
    func onActivityChanged(activity: ActivityType)

    /**
     * Called when the stationary duration exceeds the configured threshold.
     *
     * - Parameter durationMs: The duration in milliseconds that the device has been stationary
     */
    func onStationaryDurationExceeded(durationMs: Int64)
}

/**
 * iOS Activity Recognition Handler that wraps CMMotionActivityManager.
 * Detects user activity (stationary, walking, automotive) and tracks stationary duration
 * to support Motion Sleep Mode for battery optimization.
 *
 * Requirements: 8.1, 8.3
 */
class ActivityRecognitionHandler {

    // MARK: - Properties

    weak var delegate: ActivityStateDelegate?

    /// Threshold in milliseconds for stationary duration notification (default: 3 minutes)
    var stationaryThresholdMs: Int64 = 180_000

    private let motionActivityManager: CMMotionActivityManager
    private let operationQueue: OperationQueue

    private var currentActivity: ActivityType = .unknown
    private var stationaryStartTime: Date?
    private var isStationary: Bool = false
    private var isRunning: Bool = false
    private var stationaryDurationExceededNotified: Bool = false

    // MARK: - Initialization

    init() {
        motionActivityManager = CMMotionActivityManager()
        operationQueue = OperationQueue()
        operationQueue.name = "com.livetracking.activityRecognition"
        operationQueue.maxConcurrentOperationCount = 1
    }

    // MARK: - Public Methods

    /**
     * Get the current detected activity type.
     *
     * - Returns: The current activity type
     */
    func getCurrentActivity() -> ActivityType {
        return currentActivity
    }

    /**
     * Get the current stationary duration in milliseconds.
     * Returns 0 if the device is not currently stationary.
     *
     * - Returns: Duration in milliseconds that the device has been stationary, or 0
     */
    func getStationaryDurationMs() -> Int64 {
        guard isStationary, let startTime = stationaryStartTime else {
            return 0
        }
        return Int64(Date().timeIntervalSince(startTime) * 1000)
    }

    /**
     * Check if the device is currently in stationary state.
     *
     * - Returns: true if the device is detected as stationary
     */
    func isDeviceStationary() -> Bool {
        return isStationary
    }

    /**
     * Start activity recognition updates.
     * Uses CMMotionActivityManager to receive periodic activity detection updates.
     *
     * Note: Requires Motion & Fitness permission (NSMotionUsageDescription in Info.plist).
     */
    func startActivityRecognition() {
        guard CMMotionActivityManager.isActivityAvailable() else {
            print("[ActivityRecognitionHandler] Activity recognition is not available on this device")
            return
        }

        if isRunning { return }

        motionActivityManager.startActivityUpdates(to: operationQueue) { [weak self] activity in
            guard let self = self, let activity = activity else { return }
            self.handleActivityUpdate(activity)
        }

        isRunning = true
    }

    /**
     * Stop activity recognition updates.
     * Stops CMMotionActivityManager updates and resets internal state.
     */
    func stopActivityRecognition() {
        if !isRunning { return }

        motionActivityManager.stopActivityUpdates()

        // Reset state
        isRunning = false
        isStationary = false
        stationaryStartTime = nil
        stationaryDurationExceededNotified = false
        currentActivity = .unknown
    }

    // MARK: - Private Methods

    private func handleActivityUpdate(_ activity: CMMotionActivity) {
        let newActivity = mapToActivityType(activity)
        let previousActivity = currentActivity
        currentActivity = newActivity

        if newActivity != previousActivity {
            delegate?.onActivityChanged(activity: newActivity)
        }

        switch newActivity {
        case .stationary:
            handleStationaryState()
        case .walking, .automotive:
            handleMovingState()
        case .unknown:
            break
        }
    }

    private func handleStationaryState() {
        if !isStationary {
            // Transition to stationary - start tracking duration
            isStationary = true
            stationaryStartTime = Date()
            stationaryDurationExceededNotified = false
        } else {
            // Already stationary - check if threshold exceeded
            let durationMs = getStationaryDurationMs()
            if durationMs >= stationaryThresholdMs && !stationaryDurationExceededNotified {
                stationaryDurationExceededNotified = true
                delegate?.onStationaryDurationExceeded(durationMs: durationMs)
            }
        }
    }

    private func handleMovingState() {
        if isStationary {
            // Transition from stationary to moving
            isStationary = false
            stationaryStartTime = nil
            stationaryDurationExceededNotified = false
        }
    }

    private func mapToActivityType(_ activity: CMMotionActivity) -> ActivityType {
        if activity.stationary {
            return .stationary
        } else if activity.walking || activity.running {
            return .walking
        } else if activity.automotive {
            return .automotive
        } else {
            return .unknown
        }
    }
}
