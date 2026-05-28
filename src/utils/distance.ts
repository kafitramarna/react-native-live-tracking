/**
 * Distance calculation utility using the Haversine formula.
 *
 * The Haversine formula determines the great-circle distance between two points
 * on a sphere given their longitudes and latitudes. This is used by the
 * Distance/Time Matrix filter to determine if a new location update has moved
 * far enough from the last known position.
 *
 * @packageDocumentation
 */

/**
 * Earth's mean radius in meters.
 * Used as the sphere radius in the Haversine formula.
 */
export const EARTH_RADIUS_METERS = 6371000;

/**
 * Converts degrees to radians.
 *
 * @param degrees - Angle in degrees
 * @returns Angle in radians
 */
function toRadians(degrees: number): number {
  return degrees * (Math.PI / 180);
}

/**
 * Calculates the distance in meters between two geographic coordinates
 * using the Haversine formula.
 *
 * The Haversine formula accounts for the curvature of the Earth and provides
 * accurate results for short and medium distances. It assumes a spherical Earth
 * with a mean radius of 6,371,000 meters.
 *
 * @param lat1 - Latitude of the first point in degrees (-90 to 90)
 * @param lng1 - Longitude of the first point in degrees (-180 to 180)
 * @param lat2 - Latitude of the second point in degrees (-90 to 90)
 * @param lng2 - Longitude of the second point in degrees (-180 to 180)
 * @returns Distance between the two points in meters
 *
 * @example
 * ```typescript
 * // Distance between Jakarta and Bandung (approximately 120 km)
 * const distance = calculateDistance(-6.2088, 106.8456, -6.9175, 107.6191);
 * console.log(distance); // ~120,000 meters
 * ```
 */
export function calculateDistance(
  lat1: number,
  lng1: number,
  lat2: number,
  lng2: number
): number {
  const dLat = toRadians(lat2 - lat1);
  const dLng = toRadians(lng2 - lng1);

  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRadians(lat1)) *
      Math.cos(toRadians(lat2)) *
      Math.sin(dLng / 2) *
      Math.sin(dLng / 2);

  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

  return EARTH_RADIUS_METERS * c;
}
