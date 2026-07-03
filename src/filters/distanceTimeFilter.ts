/**
 * Distance/Time Matrix filter for location updates.
 *
 * This filter implements the battery optimization strategy that accepts a new
 * location update based on the selected mode:
 * - 'both' (default): BOTH time and distance conditions must be met
 * - 'interval': only the time condition must be met
 * - 'distance': only the distance condition must be met
 *
 * @packageDocumentation
 */

import { calculateDistance } from '../utils/distance';
import type { LocationData, OptimizationConfig } from '../types';

/**
 * Configuration subset required by the distance/time filter.
 */
export type DistanceTimeFilterConfig = Required<
  Pick<OptimizationConfig, 'intervalMs' | 'distanceFilterMeters'>
> & {
  /** Filter strategy. Defaults to 'both' if not provided. */
  mode?: 'interval' | 'distance' | 'both';
};

/**
 * Determines whether a new location should be accepted based on the
 * Distance/Time Matrix filter criteria.
 *
 * The filtering strategy depends on `config.mode`:
 * - 'both': accepted only if both time and distance conditions are met
 * - 'interval': accepted only if enough time has elapsed
 * - 'distance': accepted only if enough distance has been covered
 *
 * @param lastLocation - The most recently accepted location
 * @param newLocation - The candidate location to evaluate
 * @param config - Filter configuration with intervalMs, distanceFilterMeters, and mode
 * @returns `true` if the new location should be accepted, `false` otherwise
 *
 * @example
 * ```typescript
 * const accepted = shouldAcceptLocation(
 *   { latitude: -6.2088, longitude: 106.8456, timestamp: 1700000000000, accuracy: 5, speed: null, altitude: null, bearing: null },
 *   { latitude: -6.2090, longitude: 106.8460, timestamp: 1700000015000, accuracy: 5, speed: 1.2, altitude: null, bearing: null },
 *   { intervalMs: 10000, distanceFilterMeters: 10, mode: 'both' }
 * );
 * ```
 */
export function shouldAcceptLocation(
  lastLocation: LocationData,
  newLocation: LocationData,
  config: DistanceTimeFilterConfig
): boolean {
  const mode = config.mode ?? 'both';
  const timeDiff = newLocation.timestamp - lastLocation.timestamp;
  const distance = calculateDistance(
    lastLocation.latitude,
    lastLocation.longitude,
    newLocation.latitude,
    newLocation.longitude
  );

  const timeMet = timeDiff >= config.intervalMs;
  const distanceMet = distance >= config.distanceFilterMeters;

  switch (mode) {
    case 'interval':
      return timeMet;
    case 'distance':
      return distanceMet;
    case 'both':
    default:
      return timeMet && distanceMet;
  }
}
