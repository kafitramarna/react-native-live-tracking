import Foundation
import CoreLocation

/**
 * Protocol for receiving location updates and errors from the LocationEngine.
 */
protocol LocationUpdateDelegate: AnyObject {
    func onLocationReceived(location: CLLocation)
    func onLocationError(errorCode: String, message: String)
}

/**
 * Default implementation for optional delegate method so existing code keeps compiling.
 */
extension LocationUpdateDelegate {
    func onLocationError(errorCode: String, message: String) {
        // No-op by default
    }
}

/**
 * iOS Location Engine that wraps CLLocationManager.
 * Provides high-accuracy location updates for live tracking with background support.
 *
 * Requirements: 3.1, 3.2
 */
class LocationEngine: NSObject, CLLocationManagerDelegate {

    private var locationManager: CLLocationManager!

    weak var delegate: LocationUpdateDelegate?

    override init() {
        super.init()
        let initOnMain = { [self] in
            self.locationManager = CLLocationManager()
            self.locationManager.delegate = self
        }
        if Thread.isMainThread {
            initOnMain()
        } else {
            DispatchQueue.main.sync(execute: initOnMain)
        }
    }

    // MARK: - Public Methods

    func requestAlwaysAuthorization() {
        if Thread.isMainThread {
            self.locationManager.requestAlwaysAuthorization()
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.locationManager.requestAlwaysAuthorization()
            }
        }
    }

    func getAuthorizationStatus() -> CLAuthorizationStatus {
        if #available(iOS 14.0, *) {
            return locationManager.authorizationStatus
        } else {
            return CLLocationManager.authorizationStatus()
        }
    }

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
        let setup = { [weak self] in
            guard let self = self else { return }
            self.locationManager.desiredAccuracy = accuracy
            self.locationManager.distanceFilter = distanceFilter
            self.locationManager.allowsBackgroundLocationUpdates = true
            self.locationManager.pausesLocationUpdatesAutomatically = false
            // Use otherNavigation so iOS does not throttle/suspend updates when device is stationary
            self.locationManager.activityType = .otherNavigation
            self.locationManager.showsBackgroundLocationIndicator = true
            self.locationManager.startUpdatingLocation()
        }
        if Thread.isMainThread {
            setup()
        } else {
            DispatchQueue.main.async(execute: setup)
        }
    }

    /**
     * Stop receiving location updates.
     */
    func stopLocationUpdates() {
        if Thread.isMainThread {
            locationManager.stopUpdatingLocation()
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.locationManager.stopUpdatingLocation()
            }
        }
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        print("[LocationEngine] 📡 didUpdateLocations called — delegate alive=\(delegate != nil) lat=\(location.coordinate.latitude) ts=\(Int64(location.timestamp.timeIntervalSince1970 * 1000))")
        delegate?.onLocationReceived(location: location)
    }

    func locationManagerDidPauseLocationUpdates(_ manager: CLLocationManager) {
        print("[LocationEngine] ⚠️ CLLocationManager PAUSED location updates!")
    }

    func locationManagerDidResumeLocationUpdates(_ manager: CLLocationManager) {
        print("[LocationEngine] ✅ CLLocationManager RESUMED location updates")
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let errorCode: String
        let message: String

        if let clError = error as? CLError {
            switch clError.code {
            case .denied:
                errorCode = "PERMISSION_DENIED"
                message = "Location updates failed: permission denied."
            case .locationUnknown:
                errorCode = "LOCATION_UNKNOWN"
                message = "Location temporarily unavailable: \(error.localizedDescription)"
            case .network:
                errorCode = "NETWORK_ERROR"
                message = "Location network error: \(error.localizedDescription)"
            default:
                errorCode = "LOCATION_ERROR"
                message = "Location update failed: \(error.localizedDescription)"
            }
        } else {
            errorCode = "LOCATION_ERROR"
            message = "Location update failed: \(error.localizedDescription)"
        }

        delegate?.onLocationError(errorCode: errorCode, message: message)
        print("[LocationEngine] \(errorCode): \(message)")
    }
}
