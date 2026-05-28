package com.livetracking.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Represents the result of a permission check.
 */
sealed class PermissionResult {
    /** All required permissions are granted. */
    object Granted : PermissionResult()

    /** One or more permissions are denied or a required service is disabled. */
    data class Denied(
        val errorCode: String,
        val message: String
    ) : PermissionResult()
}

/**
 * Handles location permission checks for the LiveTracking library.
 * Provides clear error codes when permissions are denied or GPS is disabled.
 *
 * Validates: Requirements 10.1, 10.2
 */
class PermissionHandler {

    companion object {
        const val ERROR_PERMISSION_DENIED = "PERMISSION_DENIED"
        const val ERROR_GPS_DISABLED = "GPS_DISABLED"
    }

    /**
     * Checks if fine and coarse location permissions are granted.
     *
     * @param context Android context used for permission checks
     * @return [PermissionResult.Granted] if both ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION
     *         are granted, [PermissionResult.Denied] otherwise with error code PERMISSION_DENIED
     */
    fun checkLocationPermissions(context: Context): PermissionResult {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return if (fineLocationGranted && coarseLocationGranted) {
            PermissionResult.Granted
        } else {
            val deniedPermissions = mutableListOf<String>()
            if (!fineLocationGranted) deniedPermissions.add("ACCESS_FINE_LOCATION")
            if (!coarseLocationGranted) deniedPermissions.add("ACCESS_COARSE_LOCATION")

            PermissionResult.Denied(
                errorCode = ERROR_PERMISSION_DENIED,
                message = "Location permissions denied: ${deniedPermissions.joinToString(", ")}. " +
                    "Please grant location permissions to enable tracking."
            )
        }
    }

    /**
     * Checks if background location permission is granted (Android 10+ / API 29+).
     * On devices below Android 10, background location is implicitly granted
     * when foreground location is granted.
     *
     * @param context Android context used for permission checks
     * @return [PermissionResult.Granted] if background location is available,
     *         [PermissionResult.Denied] otherwise with error code PERMISSION_DENIED
     */
    fun checkBackgroundLocationPermission(context: Context): PermissionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Below Android 10, background location is granted with foreground location
            return PermissionResult.Granted
        }

        val backgroundLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return if (backgroundLocationGranted) {
            PermissionResult.Granted
        } else {
            PermissionResult.Denied(
                errorCode = ERROR_PERMISSION_DENIED,
                message = "Background location permission denied: ACCESS_BACKGROUND_LOCATION. " +
                    "Please grant 'Allow all the time' location permission for background tracking."
            )
        }
    }

    /**
     * Checks if GPS/location services are enabled on the device.
     *
     * @param context Android context used to access LocationManager
     * @return true if GPS provider is enabled, false otherwise
     */
    fun isGpsEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    /**
     * Performs a comprehensive check of all permissions and services required for tracking.
     * Checks location permissions, background location, and GPS status in order.
     *
     * @param context Android context
     * @return [PermissionResult.Granted] if all checks pass, or the first
     *         [PermissionResult.Denied] encountered
     */
    fun checkAllTrackingRequirements(context: Context): PermissionResult {
        // Check foreground location permissions first
        val locationResult = checkLocationPermissions(context)
        if (locationResult is PermissionResult.Denied) {
            return locationResult
        }

        // Check background location permission
        val backgroundResult = checkBackgroundLocationPermission(context)
        if (backgroundResult is PermissionResult.Denied) {
            return backgroundResult
        }

        // Check GPS is enabled
        if (!isGpsEnabled(context)) {
            return PermissionResult.Denied(
                errorCode = ERROR_GPS_DISABLED,
                message = "GPS/Location services are disabled. " +
                    "Please enable location services in device settings."
            )
        }

        return PermissionResult.Granted
    }
}
