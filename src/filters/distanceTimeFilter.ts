/**
 * Distance/Time Matrix filter for location updates.
 *
 * This filter implements the battery optimization strategy that only accepts
 * a new location update if BOTH conditions are met:
 * 1. Sufficient time has elapsed since the last update (>= intervalMs)
 * 2. Sufficient distance has been covered since the last update (>= distanceFilterMeters)
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
>;

/**
 * Determines whether a new location should be accepted based on the
 * Distance/Time Matrix filter criteria.
 *
 * A location is accepted if and only if BOTH conditions are satisfied:
 * - The time difference between the new and last location is >= intervalMs
 * - The distance between the new and last location is >= distanceFilterMeters
 *
 * @param lastLocation - The most recently accepted location
 * @param newLocation - The candidate location to evaluate
 * @param config - Filter configuration with intervalMs and distanceFilterMeters
 * @returns `true` if the new location should be accepted, `false` otherwise
 *
 * @example
 * ```typescript
 * const accepted = shouldAcceptLocation(
 *   { latitude: -6.2088, longitude: 106.8456, timestamp: 1700000000000, accuracy: 5, speed: null, altitude: null, bearing: null },
 *   { latitude: -6.2090, longitude: 106.8460, timestamp: 1700000015000, accuracy: 5, speed: 1.2, altitude: null, bearing: null },
 *   { intervalMs: 10000, distanceFilterMeters: 10 }
 * );
 * ```
 */
export function shouldAcceptLocation(
  lastLocation: LocationData,
  newLocation: LocationData,
  config: DistanceTimeFilterConfig
): boolean {
  const timeDiff = newLocation.timestamp - lastLocation.timestamp;
  const distance = calculateDistance(
    lastLocation.latitude,
    lastLocation.longitude,
    newLocation.latitude,
    newLocation.longitude
  );

  return timeDiff >= config.intervalMs && distance >= config.distanceFilterMeters;
}
