import Foundation
import CoreLocation

/**
 * Protocol defining the interface for background mode helpers that manage
 * significant location change monitoring.
 *
 * This protocol is implemented by BackgroundModeHelper (task 11.2) and allows
 * TrackingCleanup to stop significant location monitoring during cleanup.
 */
protocol SignificantLocationMonitoring: AnyObject {
    func stopSignificantLocationMonitoring(locationManager: CLLocationManager)
}

/**
 * TrackingCleanup handles stopping and cleaning up all iOS tracking resources.
 *
 * This class is called when `LiveTracking.stop()` is invoked from the JavaScript layer.
 * It coordinates the shutdown of:
 * - CLLocationManager standard location updates
 * - Significant location change monitoring (used as fallback when app is terminated)
 * - Any internal state or references held during active tracking
 *
 * Requirements: 3.4
 */
class TrackingCleanup {

    /// Tracks whether tracking is currently active
    private var isTrackingActive: Bool = false

    /// Reference to the location manager used for significant location monitoring cleanup
    private weak var locationManager: CLLocationManager?

    // MARK: - Public Methods

    /**
     * Stops all active tracking by shutting down the location engine and
     * significant location monitoring.
     *
     * This method:
     * 1. Stops CLLocationManager standard location updates via LocationEngine
     * 2. Stops significant location change monitoring via the background mode helper
     * 3. Updates internal tracking state
     *
     * - Parameter locationEngine: The LocationEngine instance managing CLLocationManager updates
     * - Parameter backgroundHelper: The helper managing significant location change monitoring
     * - Parameter locationManager: The CLLocationManager instance used for significant monitoring
     */
    func stopAllTracking(
        locationEngine: LocationEngine,
        backgroundHelper: SignificantLocationMonitoring,
        locationManager: CLLocationManager
    ) {
        // Stop standard location updates from CLLocationManager
        locationEngine.stopLocationUpdates()

        // Stop significant location change monitoring (fallback for app termination)
        backgroundHelper.stopSignificantLocationMonitoring(locationManager: locationManager)

        // Update internal state
        isTrackingActive = false
        self.locationManager = nil
    }

    /**
     * Resets all internal state and releases references.
     *
     * Call this method after `stopAllTracking` to ensure no stale references
     * are retained. This is useful for full teardown when the module is being
     * deallocated or when a fresh start is needed.
     */
    func cleanup() {
        isTrackingActive = false
        locationManager = nil
    }

    // MARK: - Internal State

    /**
     * Marks tracking as active. Called when `LiveTracking.start()` begins tracking.
     */
    func markTrackingActive(locationManager: CLLocationManager) {
        isTrackingActive = true
        self.locationManager = locationManager
    }

    /**
     * Returns whether tracking is currently active.
     */
    func isActive() -> Bool {
        return isTrackingActive
    }
}
