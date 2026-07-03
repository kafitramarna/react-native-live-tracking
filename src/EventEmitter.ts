/**
 * Event listener system for react-native-live-tracking.
 *
 * Wraps NativeEventEmitter to provide typed event subscriptions
 * for location updates and tracking errors.
 *
 * @packageDocumentation
 */

import { DeviceEventEmitter } from 'react-native';
import type { LocationData, TrackingError, Subscription } from './types';

/**
 * Directly use the global device event emitter.
 *
 * NativeEventEmitter's constructor accesses Platform.OS, which requires the
 * PlatformConstants native module. In a broken or partially-initialised binary
 * that module can be missing and crash the app before we can even report that
 * LiveTracking is unavailable. DeviceEventEmitter avoids that dependency
 * entirely while still delivering native events emitted by the LiveTracking
 * RCTEventEmitter.
 */

/**
 * Register a callback for location updates.
 *
 * The callback is invoked each time a valid location update is emitted
 * from the native layer after passing the Distance/Time Matrix filter.
 *
 * @param callback - Function called with LocationData on each update
 * @returns Subscription with `remove()` method to unsubscribe
 *
 * @example
 * ```ts
 * const subscription = onLocationUpdate((location) => {
 *   console.log(location.latitude, location.longitude);
 * });
 * // Later: unsubscribe
 * subscription.remove();
 * ```
 */
export function onLocationUpdate(
  callback: (location: LocationData) => void
): Subscription {
  const nativeSubscription = DeviceEventEmitter.addListener(
    'onLocationUpdate',
    (event: Record<string, unknown>) => {
      const location: LocationData = {
        latitude: event.latitude as number,
        longitude: event.longitude as number,
        timestamp: event.timestamp as number,
        accuracy: event.accuracy as number,
        speed: (event.speed as number | null) ?? null,
        altitude: (event.altitude as number | null) ?? null,
        bearing: (event.bearing as number | null) ?? null,
      };
      callback(location);
    }
  );

  return {
    remove: () => {
      nativeSubscription.remove();
    },
  };
}

/**
 * Register a callback for tracking errors.
 *
 * The callback is invoked when an error occurs during tracking
 * (e.g., permission denied, GPS disabled, Firebase write failure).
 *
 * @param callback - Function called with TrackingError on each error event
 * @returns Subscription with `remove()` method to unsubscribe
 *
 * @example
 * ```ts
 * const subscription = onError((error) => {
 *   console.error(`[${error.code}] ${error.message}`);
 * });
 * // Later: unsubscribe
 * subscription.remove();
 * ```
 */
export function onError(
  callback: (error: TrackingError) => void
): Subscription {
  const nativeSubscription = DeviceEventEmitter.addListener(
    'onTrackingError',
    (event: Record<string, unknown>) => {
      const error: TrackingError = {
        code: event.code as string,
        message: event.message as string,
        recoverable: (event.recoverable as boolean) ?? true,
        details: (event.details as Record<string, unknown>) ?? undefined,
      };
      callback(error);
    }
  );

  return {
    remove: () => {
      nativeSubscription.remove();
    },
  };
}

/**
 * Remove all event listeners registered through this module.
 *
 * Useful for cleanup when the tracking module is being torn down
 * or when all subscriptions need to be cleared at once.
 */
export function removeAllListeners(): void {
  DeviceEventEmitter.removeAllListeners('onLocationUpdate');
  DeviceEventEmitter.removeAllListeners('onTrackingError');
}
