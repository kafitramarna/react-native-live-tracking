/**
 * Location serialization for Firebase paths.
 *
 * Transforms LocationData into the appropriate format for
 * current location (overwrite) and history (append) paths.
 *
 * @packageDocumentation
 */

import type { LocationData } from '../types';

// ─── Payload Interfaces ──────────────────────────────────────────────────────

/**
 * Payload structure for the current location path (overwrite mode).
 * Contains the latest position with an updatedAt timestamp.
 */
export interface CurrentLocationPayload {
  latitude: number;
  longitude: number;
  timestamp: number;
  accuracy: number;
  updatedAt: number;
}

/**
 * Payload structure for the history path (append mode).
 * Contains full location data including speed, altitude, and bearing.
 */
export interface HistoryLocationPayload {
  latitude: number;
  longitude: number;
  timestamp: number;
  accuracy: number;
  speed: number | null;
  altitude: number | null;
  bearing: number | null;
}

// ─── Serialization Functions ─────────────────────────────────────────────────

/**
 * Serialize a LocationData object for the Firebase current location path.
 *
 * The current location path uses overwrite (set/update) mode and includes
 * an `updatedAt` field set to the location's timestamp.
 *
 * @param location - The location data to serialize
 * @returns Serialized payload for the current location path
 */
export function serializeForCurrentPath(
  location: LocationData
): CurrentLocationPayload {
  return {
    latitude: location.latitude,
    longitude: location.longitude,
    timestamp: location.timestamp,
    accuracy: location.accuracy,
    updatedAt: location.timestamp,
  };
}

/**
 * Serialize a LocationData object for the Firebase history path.
 *
 * The history path uses append (push) mode and includes all available
 * location fields: speed, altitude, and bearing.
 *
 * @param location - The location data to serialize
 * @returns Serialized payload for the history path
 */
export function serializeForHistoryPath(
  location: LocationData
): HistoryLocationPayload {
  return {
    latitude: location.latitude,
    longitude: location.longitude,
    timestamp: location.timestamp,
    accuracy: location.accuracy,
    speed: location.speed,
    altitude: location.altitude,
    bearing: location.bearing,
  };
}
