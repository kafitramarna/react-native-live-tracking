/**
 * Location serialization for Firebase sync targets.
 *
 * Transforms LocationData into a generic payload suitable for any
 * sync target, regardless of write method (set, push, update).
 *
 * @packageDocumentation
 */

import type { LocationData } from '../types';

// ─── Payload Interface ───────────────────────────────────────────────────────

/**
 * Serialized location payload for any sync target.
 *
 * Contains required fields (latitude, longitude, timestamp, accuracy)
 * and optional sensor fields (speed, heading, altitude) which are
 * included as `null` when unavailable from the device sensor.
 * Placeholder values (0, -1) are never used for unavailable fields.
 */
export interface TargetLocationPayload {
  latitude: number;
  longitude: number;
  timestamp: number;
  accuracy: number;
  speed: number | null;
  heading: number | null;
  altitude: number | null;
}

// ─── Serialization Function ──────────────────────────────────────────────────

/**
 * Serialize a LocationData object for a generic Firebase sync target.
 *
 * Produces a payload containing all required location fields and optional
 * sensor fields. When optional fields (speed, heading/bearing, altitude)
 * are unavailable from the device sensor, they are included as `null`.
 * Default placeholder values (0, -1) are never written for unavailable fields.
 *
 * @param location - The location data to serialize
 * @returns Serialized payload for the sync target
 */
export function serializeLocationForTarget(
  location: LocationData
): TargetLocationPayload {
  return {
    latitude: location.latitude,
    longitude: location.longitude,
    timestamp: location.timestamp,
    accuracy: location.accuracy,
    speed: location.speed,
    heading: location.bearing,
    altitude: location.altitude,
  };
}
