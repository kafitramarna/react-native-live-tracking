package com.livetracking.location

import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Android Location Engine that wraps FusedLocationProviderClient.
 * Provides high-accuracy location updates for live tracking.
 *
 * Requirements: 2.2
 */
class LocationEngine(context: Context) {

    /**
     * Listener interface for receiving location updates from the engine.
     */
    interface LocationUpdateListener {
        fun onLocationReceived(location: Location)
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationListener: LocationUpdateListener? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            locationListener?.onLocationReceived(location)
        }
    }

    /**
     * Set the listener that will receive location updates.
     *
     * @param listener The listener to receive location callbacks
     */
    fun setLocationListener(listener: LocationUpdateListener) {
        this.locationListener = listener
    }

    /**
     * Start receiving location updates with the specified interval and distance filter.
     * Uses PRIORITY_HIGH_ACCURACY for best possible location accuracy.
     *
     * @param intervalMs The desired interval for location updates in milliseconds
     * @param distanceFilter The minimum distance between updates in meters
     * @throws SecurityException if location permissions are not granted
     */
    @Throws(SecurityException::class)
    fun startLocationUpdates(intervalMs: Long, distanceFilter: Float) {
        startLocationUpdates(intervalMs, distanceFilter, Priority.PRIORITY_HIGH_ACCURACY)
    }

    /**
     * Start receiving location updates with the specified interval, distance filter, and priority.
     *
     * @param intervalMs The desired interval for location updates in milliseconds
     * @param distanceFilter The minimum distance between updates in meters
     * @param priority The location request priority (e.g., Priority.PRIORITY_HIGH_ACCURACY or Priority.PRIORITY_LOW_POWER)
     * @throws SecurityException if location permissions are not granted
     */
    @Throws(SecurityException::class)
    fun startLocationUpdates(intervalMs: Long, distanceFilter: Float, priority: Int) {
        val locationRequest = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateDistanceMeters(distanceFilter)
            .setWaitForAccurateLocation(false)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    /**
     * Stop receiving location updates and remove the location callback.
     */
    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
