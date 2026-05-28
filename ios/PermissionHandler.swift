import Foundation
import CoreLocation

/**
 * Represents the result of a permission or service check.
 */
enum PermissionResult {
    /// All required permissions/services are available.
    case granted
    /// A permission is denied or a required service is disabled.
    case denied(errorCode: String, message: String)
}

/**
 * Handles location permission checks for the LiveTracking library on iOS.
 * Provides clear error codes when permissions are denied or GPS is disabled.
 *
 * Validates: Requirements 10.1, 10.2
 */
class PermissionHandler {

    // MARK: - Error Code Constants

    static let ERROR_PERMISSION_DENIED = "PERMISSION_DENIED"
    static let ERROR_GPS_DISABLED = "GPS_DISABLED"

    // MARK: - Private Properties

    private let locationManager: CLLocationManager

    // MARK: - Initialization

    init(locationManager: CLLocationManager = CLLocationManager()) {
        self.locationManager = locationManager
    }

    // MARK: - Permission Checks

    /**
     * Checks the current location permission status.
     *
     * Uses `CLLocationManager.authorizationStatus()` for iOS < 14,
     * and `locationManager.authorizationStatus` for iOS 14+.
     *
     * - Returns: `.granted` if authorized (whenInUse or always),
     *            `.denied` with PERMISSION_DENIED error code otherwise.
     */
    func checkLocationPermission() -> PermissionResult {
        let status: CLAuthorizationStatus

        if #available(iOS 14.0, *) {
            status = locationManager.authorizationStatus
        } else {
            status = CLLocationManager.authorizationStatus()
        }

        switch status {
        case .authorizedWhenInUse, .authorizedAlways:
            return .granted

        case .denied:
            return .denied(
                errorCode: PermissionHandler.ERROR_PERMISSION_DENIED,
                message: "Location permission denied by user. Please grant location permission in Settings to enable tracking."
            )

        case .restricted:
            return .denied(
                errorCode: PermissionHandler.ERROR_PERMISSION_DENIED,
                message: "Location permission restricted. Location services may be restricted by parental controls or device policy."
            )

        case .notDetermined:
            return .denied(
                errorCode: PermissionHandler.ERROR_PERMISSION_DENIED,
                message: "Location permission not determined. Please request location permission before starting tracking."
            )

        @unknown default:
            return .denied(
                errorCode: PermissionHandler.ERROR_PERMISSION_DENIED,
                message: "Unknown location authorization status. Please check location permissions in Settings."
            )
        }
    }

    /**
     * Checks if location services are enabled on the device.
     *
     * - Returns: `.granted` if location services are enabled,
     *            `.denied` with GPS_DISABLED error code if disabled.
     */
    func checkLocationServicesEnabled() -> PermissionResult {
        if CLLocationManager.locationServicesEnabled() {
            return .granted
        } else {
            return .denied(
                errorCode: PermissionHandler.ERROR_GPS_DISABLED,
                message: "GPS/Location services are disabled. Please enable location services in device Settings."
            )
        }
    }

    /**
     * Performs a comprehensive check of all requirements for location tracking.
     * Checks location services enabled first, then permission status.
     *
     * - Returns: `.granted` if all checks pass, or the first `.denied` encountered.
     */
    func checkAllRequirements() -> PermissionResult {
        // Check location services enabled first
        let servicesResult = checkLocationServicesEnabled()
        if case .denied = servicesResult {
            return servicesResult
        }

        // Then check permission status
        let permissionResult = checkLocationPermission()
        if case .denied = permissionResult {
            return permissionResult
        }

        return .granted
    }

    /**
     * Requests "Always" location authorization from the user.
     * This enables background location updates.
     *
     * Note: NSLocationAlwaysAndWhenInUseUsageDescription must be configured in Info.plist.
     *
     * - Parameter locationManager: The CLLocationManager instance to request authorization on.
     */
    func requestAlwaysAuthorization(locationManager: CLLocationManager) {
        locationManager.requestAlwaysAuthorization()
    }

    /**
     * Requests "When In Use" location authorization from the user.
     *
     * Note: NSLocationWhenInUseUsageDescription must be configured in Info.plist.
     *
     * - Parameter locationManager: The CLLocationManager instance to request authorization on.
     */
    func requestWhenInUseAuthorization(locationManager: CLLocationManager) {
        locationManager.requestWhenInUseAuthorization()
    }
}
