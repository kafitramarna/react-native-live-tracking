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
 * Returns the native module or throws a descriptive error if not linked.
 * Using a function accessor (rather than assertNative) keeps TypeScript
 * happy since it can't narrow an imported binding via assertion functions.
 */
function native(): NonNullable<typeof NativeLiveTracking> {
  if (!NativeLiveTracking) {
    throw new Error(
      "[@kafitra/react-native-live-tracking] Native module 'LiveTracking' is not registered. " +
        'Make sure you ran `pod install` and rebuilt the app from Xcode.'
    );
  }
  return NativeLiveTracking;
}

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
   */
  async configure(config: TrackingConfig): Promise<void> {
    const validationResult = validateConfig(config);
    if (!validationResult.valid) {
      const errorMessages = validationResult.errors
        .map((e) => `[${e.field}]: ${e.message}`)
        .join('; ');
      throw new Error(`Invalid configuration: ${errorMessages}`);
    }

    const finalConfig = applyDefaults(config);
    const jsonString = JSON.stringify(finalConfig);

    const MAX_PAYLOAD_BYTES = 1024 * 1024; // 1 MB
    if (jsonString.length > MAX_PAYLOAD_BYTES) {
      throw new Error(
        'Configuration payload is too large: serialized JSON exceeds 1 MB'
      );
    }

    await native().configure(jsonString);
    isConfigured = true;
  },

  /**
   * Start location tracking.
   * Requires `configure()` to have been called first.
   */
  async start(): Promise<void> {
    await native().start();
  },

  /**
   * Stop location tracking and clean up resources.
   */
  async stop(): Promise<void> {
    await native().stop();
  },

  /**
   * Register a callback for location updates.
   */
  onLocationUpdate(callback: (location: LocationData) => void): Subscription {
    return emitterOnLocationUpdate(callback);
  },

  /**
   * Register a callback for tracking errors.
   */
  onError(callback: (error: TrackingError) => void): Subscription {
    return emitterOnError(callback);
  },

  /**
   * Get the current tracking status.
   */
  async getStatus(): Promise<TrackingStatus> {
    const jsonString = await native().getStatus();
    return JSON.parse(jsonString) as TrackingStatus;
  },

  /**
   * Get the total number of locations currently queued for sync.
   *
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
    return native().getQueuedLocations();
  },

  /**
   * Get the number of queued locations per target path.
   *
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
    const jsonString = await native().getQueuedLocationsByTarget();
    return JSON.parse(jsonString);
  },
};

export default LiveTracking;
