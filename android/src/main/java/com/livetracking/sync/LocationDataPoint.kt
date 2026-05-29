package com.livetracking.sync

/**
 * Represents a single location data point dispatched to sync targets.
 *
 * Required fields (always available from device sensors):
 * - latitude, longitude, timestamp, accuracy
 *
 * Optional fields (may be unavailable depending on device/sensor state):
 * - speed, altitude, bearing
 *
 * When optional fields are null, they should be omitted or written as null
 * in the Firebase payload — never as placeholder values (0, -1, etc.).
 *
 * @param latitude Location latitude in degrees
 * @param longitude Location longitude in degrees
 * @param timestamp Unix timestamp in milliseconds
 * @param accuracy Location accuracy in meters
 * @param speed Speed in m/s, or null if unavailable
 * @param altitude Altitude in meters, or null if unavailable
 * @param bearing Bearing in degrees, or null if unavailable
 */
data class LocationDataPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val accuracy: Float,
    val speed: Float? = null,
    val altitude: Double? = null,
    val bearing: Float? = null
)
