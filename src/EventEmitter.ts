/**
 * Event listener system for react-native-live-tracking.
 *
 * Wraps NativeEventEmitter to provide typed event subscriptions
 * for location updates and tracking errors.
 *
 * @packageDocumentation
 */

import { NativeEventEmitter } from 'react-native';
import type { LocationData, TrackingError, Subscription } from './types';

/**
 * Lazily-created NativeEventEmitter for the LiveTracking native module.
 *
 * We intentionally do NOT pass the native module instance to NativeEventEmitter
 * constructor. Passing a module triggers RN to validate addListener/removeListeners
 * via PlatformConstants at instantiation time, which crashes in Bridgeless mode
 * before the TurboModule registry is fully initialised.
 *
 * Since LiveTracking.swift implements addListener/removeListeners on the native
 * side, events are still delivered correctly — the constructor argument is only
 * used by RN's legacy bridge to call those methods automatically.
 */
let _emitter: NativeEventEmitter | null = null;

function getEmitter(): NativeEventEmitter {
  if (!_emitter) {
    _emitter = new NativeEventEmitter();
  }
  return _emitter;
}

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
  const nativeSubscription = getEmitter().addListener(
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
  const nativeSubscription = getEmitter().addListener(
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
  getEmitter().removeAllListeners('onLocationUpdate');
  getEmitter().removeAllListeners('onTrackingError');
}
