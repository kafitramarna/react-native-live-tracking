/**
 * JavaScript LiveTracking module that wraps NativeModule calls.
 *
 * Provides the public API for configuring, starting, and stopping
 * location tracking, with validation and state management.
 *
 * @packageDocumentation
 */

import NativeLiveTracking from './NativeLiveTracking';
import {
  onLocationUpdate as emitterOnLocationUpdate,
  onError as emitterOnError,
} from './EventEmitter';
import type {
  TrackingConfig,
  TrackingStatus,
  LocationData,
  TrackingError,
  Subscription,
  LiveTrackingModule,
} from './types';
import { validateConfig, applyDefaults } from './validation';

/**
 * Tracks whether configure() has been successfully called.
 * Used to guard queue methods that require configuration.
 */
let isConfigured = false;

/**
 * Resets the configured state. Only for use in tests.
 * @internal
 */
export function _resetConfiguredStateForTesting(): void {
  isConfigured = false;
}

/**
 * LiveTracking module implementation.
 *
 * Wraps native module calls with validation, serialization,
 * and state management on the JavaScript side.
 */
const LiveTracking: LiveTrackingModule = {
  /**
   * Configure the tracking library with the given parameters.
   * Validates the config, applies defaults, and passes to native layer.
   *
   * Serializes the full firebase object (service + targets array) as JSON,
   * omitting undefined fields from each SyncTarget. Rejects if the
   * serialized payload exceeds 1 MB.
   *
   * @param config - The tracking configuration object
   * @throws Error with descriptive message if config is invalid
   * @throws Error if serialized payload exceeds 1 MB
   */
  async configure(config: TrackingConfig): Promise<void> {
    // Validate configuration
    const validationResult = validateConfig(config);
    if (!validationResult.valid) {
      const errorMessages = validationResult.errors
        .map((e) => `[${e.field}]: ${e.message}`)
        .join('; ');
      throw new Error(`Invalid configuration: ${errorMessages}`);
    }

    // Apply default values
    const finalConfig = applyDefaults(config);

    // Serialize to JSON string for native module.
    // JSON.stringify naturally omits undefined fields from SyncTarget objects.
    const jsonString = JSON.stringify(finalConfig);

    // Payload size check: reject if JSON string exceeds 1 MB
    const MAX_PAYLOAD_BYTES = 1024 * 1024; // 1 MB
    if (jsonString.length > MAX_PAYLOAD_BYTES) {
      throw new Error(
        'Configuration payload is too large: serialized JSON exceeds 1 MB'
      );
    }

    // Call native module
    await NativeLiveTracking.configure(jsonString);

    // Mark as configured after successful native call
    isConfigured = true;
  },

  /**
   * Start location tracking.
   * Requires `configure()` to have been called first.
   */
  async start(): Promise<void> {
    await NativeLiveTracking.start();
  },

  /**
   * Stop location tracking and clean up resources.
   * Stops sending events to all registered listeners.
   */
  async stop(): Promise<void> {
    await NativeLiveTracking.stop();
  },

  /**
   * Register a callback for location updates.
   *
   * The callback is invoked each time a valid location update is emitted
   * from the native layer after passing the Distance/Time Matrix filter.
   *
   * @param callback - Function called with LocationData on each update
   * @returns Subscription with `remove()` method to unsubscribe
   */
  onLocationUpdate(callback: (location: LocationData) => void): Subscription {
    return emitterOnLocationUpdate(callback);
  },

  /**
   * Register a callback for tracking errors.
   *
   * The callback is invoked when an error occurs during tracking
   * (e.g., permission denied, GPS disabled, Firebase write failure).
   *
   * @param callback - Function called with TrackingError on each error event
   * @returns Subscription with `remove()` method to unsubscribe
   */
  onError(callback: (error: TrackingError) => void): Subscription {
    return emitterOnError(callback);
  },

  /**
   * Get the current tracking status.
   * Calls native module and parses the JSON response.
   *
   * @returns The current TrackingStatus
   */
  async getStatus(): Promise<TrackingStatus> {
    const jsonString = await NativeLiveTracking.getStatus();
    const status: TrackingStatus = JSON.parse(jsonString);
    return status;
  },

  /**
   * Get the total number of locations currently queued for sync across all targets.
   *
   * @returns The total number of queued locations
   * @throws Error with code NOT_CONFIGURED if called before configure()
   */
  async getQueuedLocations(): Promise<number> {
    if (!isConfigured) {
      const error = new Error(
        'LiveTracking is not configured. Call configure() before getQueuedLocations().'
      );
      (error as any).code = 'NOT_CONFIGURED';
      throw error;
    }
    return await NativeLiveTracking.getQueuedLocations();
  },

  /**
   * Get the number of queued locations per target path.
   * Returns a record mapping each configured target path to its queued location count.
   * Targets with offlineQueue disabled report 0.
   *
   * @returns Record of target path to queued location count
   * @throws Error with code NOT_CONFIGURED if called before configure()
   */
  async getQueuedLocationsByTarget(): Promise<Record<string, number>> {
    if (!isConfigured) {
      const error = new Error(
        'LiveTracking is not configured. Call configure() before getQueuedLocationsByTarget().'
      );
      (error as any).code = 'NOT_CONFIGURED';
      throw error;
    }
    const jsonString = await NativeLiveTracking.getQueuedLocationsByTarget();
    return JSON.parse(jsonString);
  },
};

export default LiveTracking;
