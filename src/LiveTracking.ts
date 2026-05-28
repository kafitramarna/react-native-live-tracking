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
   * @param config - The tracking configuration object
   * @throws Error with descriptive message if config is invalid
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

    // Serialize to JSON string for native module
    const jsonString = JSON.stringify(finalConfig);

    // Call native module
    await NativeLiveTracking.configure(jsonString);
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
   * Get the number of locations currently queued for sync.
   *
   * @returns The number of queued locations
   */
  async getQueuedLocations(): Promise<number> {
    return await NativeLiveTracking.getQueuedLocations();
  },
};

export default LiveTracking;
