import Foundation
import CoreLocation

/**
 * BackgroundModeHelper
 *
 * Utility class that provides significant location change monitoring as a fallback
 * mechanism when the app is terminated by the system. Significant location changes
 * will relaunch the app, allowing tracking to resume.
 *
 * ## Important: Consuming App Configuration Required
 *
 * This library does NOT modify the consuming app's Info.plist. The consuming app
 * MUST add the following entries to their Info.plist:
 *
 * ### 1. UIBackgroundModes
 * Add `location` to the `UIBackgroundModes` array:
 * ```xml
 * <key>UIBackgroundModes</key>
 * <array>
 *     <string>location</string>
 * </array>
 * ```
 *
 * ### 2. Location Usage Descriptions
 * Add both usage description keys:
 * ```xml
 * <key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
 * <string>This app needs location access to track your position in the background.</string>
 * <key>NSLocationWhenInUseUsageDescription</key>
 * <string>This app needs location access to track your position.</string>
 * ```
 *
 * ### How Significant Location Change Monitoring Works
 *
 * When the app is terminated by the system (e.g., due to memory pressure), iOS can
 * still deliver significant location change events. These events will relaunch the app
 * in the background with `UIApplication.LaunchOptionsKey.location` set in the launch
 * options dictionary.
 *
 * The consuming app should check for this key in `application(_:didFinishLaunchingWithOptions:)`
 * and restart location tracking if present:
 *
 * ```swift
 * func application(_ application: UIApplication,
 *                  didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
 *     if launchOptions?[.location] != nil {
 *         // App was relaunched due to a significant location change.
 *         // Restart tracking here.
 *     }
 *     return true
 * }
 * ```
 *
 * Significant location changes are triggered by cell tower transitions and typically
 * fire every 500 meters or more. This is less precise than continuous GPS but ensures
 * the app can resume full-accuracy tracking after termination.
 */
@objc
class BackgroundModeHelper: NSObject, SignificantLocationMonitoring {

    // MARK: - Singleton

    @objc static let shared = BackgroundModeHelper()

    private override init() {
        super.init()
    }

    // MARK: - Significant Location Monitoring

    /**
     * Starts significant location change monitoring as a fallback mechanism.
     *
     * This should be called when tracking begins so that if the app is terminated
     * by the system, iOS will relaunch the app upon detecting a significant location
     * change (typically cell tower transitions, ~500m or more).
     *
     * - Parameter locationManager: The CLLocationManager instance to use for monitoring.
     *
     * - Note: The consuming app must have `UIBackgroundModes` with `location` in Info.plist
     *   and the user must have granted "Always" location permission for this to work
     *   after app termination.
     */
    @objc
    func startSignificantLocationMonitoring(locationManager: CLLocationManager) {
        guard CLLocationManager.significantLocationChangeMonitoringAvailable() else {
            return
        }
        locationManager.startMonitoringSignificantLocationChanges()
    }

    /**
     * Stops significant location change monitoring.
     *
     * Call this when tracking is stopped to prevent unnecessary app relaunches
     * after termination.
     *
     * - Parameter locationManager: The CLLocationManager instance to stop monitoring on.
     */
    @objc
    func stopSignificantLocationMonitoring(locationManager: CLLocationManager) {
        locationManager.stopMonitoringSignificantLocationChanges()
    }

    /**
     * Checks whether significant location change monitoring is available on this device.
     *
     * Significant location monitoring may not be available on all devices (e.g., devices
     * without cellular hardware). Always check availability before starting monitoring.
     *
     * - Returns: `true` if significant location change monitoring is available, `false` otherwise.
     */
    @objc
    func isSignificantLocationMonitoringAvailable() -> Bool {
        return CLLocationManager.significantLocationChangeMonitoringAvailable()
    }

    /**
     * Checks if the app was launched due to a significant location change event.
     *
     * Call this from `application(_:didFinishLaunchingWithOptions:)` to determine
     * if the app should restart tracking.
     *
     * - Parameter launchOptions: The launch options dictionary from AppDelegate.
     * - Returns: `true` if the app was relaunched due to a location event.
     */
    @objc
    func wasLaunchedDueToLocationEvent(launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        return launchOptions?[.location] != nil
    }
}
