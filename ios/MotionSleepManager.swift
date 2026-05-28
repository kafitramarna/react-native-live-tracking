import Foundation
import CoreLocation

/**
 * Protocol for receiving motion sleep mode state changes.
 */
protocol MotionSleepDelegate: AnyObject {
    /**
     * Called when sleep mode is activated (device stationary > 3 minutes).
     */
    func onSleepModeActivated()

    /**
     * Called when sleep mode is deactivated (movement detected).
     */
    func onSleepModeDeactivated()
}

/**
 * Manages Motion Sleep Mode for battery optimization on iOS.
 *
 * When the device is detected as stationary for more than 3 minutes and `stopWhenStill` is enabled,
 * this manager switches location updates to kCLDesiredAccuracyKilometer (low-power mode).
 * When movement is detected again, it restores kCLDesiredAccuracyBest location updates.
 *
 * Requirements: 8.1, 8.2, 8.4
 */
class MotionSleepManager {

    // MARK: - Constants

    /// Duration threshold in milliseconds before entering sleep mode.
    /// Device must be stationary for more than 3 minutes (180,000 ms).
    static let STILL_THRESHOLD_MS: Int64 = 180_000

    // MARK: - Properties

    weak var delegate: MotionSleepDelegate?

    private let locationEngine: LocationEngine
    private let stopWhenStill: Bool
    private let intervalMs: Int
    private let distanceFilter: Double

    private var inSleepMode: Bool = false
    private var stationaryStartTime: Date?
    private var isStationary: Bool = false

    // MARK: - Initialization

    /**
     * Initialize the MotionSleepManager.
     *
     * - Parameter locationEngine: The location engine to control GPS accuracy
     * - Parameter stopWhenStill: Whether motion sleep mode is enabled
     * - Parameter intervalMs: The configured location update interval in milliseconds
     * - Parameter distanceFilter: The configured distance filter in meters
     */
    init(locationEngine: LocationEngine, stopWhenStill: Bool, intervalMs: Int, distanceFilter: Double) {
        self.locationEngine = locationEngine
        self.stopWhenStill = stopWhenStill
        self.intervalMs = intervalMs
        self.distanceFilter = distanceFilter
    }

    // MARK: - Public Methods

    /**
     * Called when an activity detection update is received.
     *
     * If `stopWhenStill` is false, this method is a no-op.
     *
     * Behavior:
     * - stationary detected: starts tracking duration. If stationary > 3 minutes, enters sleep mode.
     * - walking or automotive detected: exits sleep mode if active, resets stationary tracking.
     *
     * - Parameter activity: The detected activity type from ActivityRecognitionHandler
     */
    func onActivityDetected(activity: ActivityType) {
        if !stopWhenStill {
            return
        }

        switch activity {
        case .stationary:
            if !isStationary {
                // Start tracking stationary duration
                isStationary = true
                stationaryStartTime = Date()
            } else {
                // Already stationary, check if threshold exceeded
                guard let startTime = stationaryStartTime else { return }
                let stationaryDurationMs = Int64(Date().timeIntervalSince(startTime) * 1000)
                if stationaryDurationMs > MotionSleepManager.STILL_THRESHOLD_MS && !inSleepMode {
                    enterSleepMode()
                }
            }

        case .walking, .automotive:
            isStationary = false
            stationaryStartTime = nil
            if inSleepMode {
                exitSleepMode()
            }

        case .unknown:
            break
        }
    }

    /**
     * Returns whether the manager is currently in sleep mode (low-power location).
     *
     * - Returns: true if sleep mode is active, false otherwise
     */
    func isInSleepMode() -> Bool {
        return inSleepMode
    }

    // MARK: - Private Methods

    /**
     * Enter sleep mode: stop current location updates and restart with kCLDesiredAccuracyKilometer.
     * This reduces GPS usage when the device is stationary.
     */
    private func enterSleepMode() {
        inSleepMode = true
        locationEngine.stopLocationUpdates()
        locationEngine.startLocationUpdates(
            intervalMs: intervalMs,
            distanceFilter: distanceFilter,
            accuracy: kCLLocationAccuracyKilometer
        )
        delegate?.onSleepModeActivated()
    }

    /**
     * Exit sleep mode: stop current location updates and restart with kCLDesiredAccuracyBest.
     * This restores full GPS accuracy when movement is detected.
     */
    private func exitSleepMode() {
        inSleepMode = false
        locationEngine.stopLocationUpdates()
        locationEngine.startLocationUpdates(
            intervalMs: intervalMs,
            distanceFilter: distanceFilter,
            accuracy: kCLLocationAccuracyBest
        )
        delegate?.onSleepModeDeactivated()
    }
}
