import Foundation

/**
 * Represents a single location data point dispatched to sync targets.
 *
 * Required fields (always available from device sensors):
 * - latitude, longitude, timestamp, accuracy
 *
 * Optional fields (may be unavailable depending on device/sensor state):
 * - speed, altitude, bearing
 *
 * When optional fields are nil, they should be omitted or written as null
 * in the Firebase payload — never as placeholder values (0, -1, etc.).
 *
 * Requirements: 9.3
 */
struct LocationDataPoint {
    /// Location latitude in degrees
    let latitude: Double

    /// Location longitude in degrees
    let longitude: Double

    /// Unix timestamp in milliseconds
    let timestamp: Int64

    /// Location accuracy in meters
    let accuracy: Double

    /// Speed in m/s, or nil if unavailable
    let speed: Double?

    /// Altitude in meters, or nil if unavailable
    let altitude: Double?

    /// Bearing in degrees, or nil if unavailable
    let bearing: Double?

    init(
        latitude: Double,
        longitude: Double,
        timestamp: Int64,
        accuracy: Double,
        speed: Double? = nil,
        altitude: Double? = nil,
        bearing: Double? = nil
    ) {
        self.latitude = latitude
        self.longitude = longitude
        self.timestamp = timestamp
        self.accuracy = accuracy
        self.speed = speed
        self.altitude = altitude
        self.bearing = bearing
    }
}
