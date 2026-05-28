import Foundation
import CoreLocation

/**
 * Protocol for receiving location updates from the LocationEngine.
 */
protocol LocationUpdateDelegate: AnyObject {
    func onLocationReceived(location: CLLocation)
}

/**
 * iOS Location Engine that wraps CLLocationManager.
 * Provides high-accuracy location updates for live tracking with background support.
 *
 * Requirements: 3.1, 3.2
 */
class LocationEngine: NSObject, CLLocationManagerDelegate {

    private let locationManager: CLLocationManager

    weak var delegate: LocationUpdateDelegate?

    override init() {
        locationManager = CLLocationManager()
        super.init()
        locationManager.delegate = self
    }

    // MARK: - Public Methods

    /**
     * Start receiving location updates with the specified interval and distance filter.
     * Uses kCLLocationAccuracyBest for best possible location accuracy.
     *
     * - Parameter intervalMs: The desired interval for location updates in milliseconds (used for time-based filtering at a higher level)
     * - Parameter distanceFilter: The minimum distance between updates in meters
     */
    func startLocationUpdates(intervalMs: Int, distanceFilter: Double) {
        startLocationUpdates(intervalMs: intervalMs, distanceFilter: distanceFilter, accuracy: kCLLocationAccuracyBest)
    }

    /**
     * Start receiving location updates with the specified interval, distance filter, and accuracy.
     * Allows configurable accuracy for scenarios like sleep mode where lower accuracy saves battery.
     *
     * - Parameter intervalMs: The desired interval for location updates in milliseconds (used for time-based filtering at a higher level)
     * - Parameter distanceFilter: The minimum distance between updates in meters
     * - Parameter accuracy: The desired location accuracy (e.g., kCLLocationAccuracyBest or kCLLocationAccuracyKilometer)
     */
    func startLocationUpdates(intervalMs: Int, distanceFilter: Double, accuracy: CLLocationAccuracy) {
        locationManager.desiredAccuracy = accuracy
        locationManager.distanceFilter = distanceFilter
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.startUpdatingLocation()
    }

    /**
     * Stop receiving location updates.
     */
    func stopLocationUpdates() {
        locationManager.stopUpdatingLocation()
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        delegate?.onLocationReceived(location: location)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Log the error for debugging purposes.
        // Error handling is propagated through the higher-level tracking module.
        print("[LocationEngine] Location update failed: \(error.localizedDescription)")
    }
}
