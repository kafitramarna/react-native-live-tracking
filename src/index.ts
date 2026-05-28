/**
 * react-native-live-tracking
 *
 * Real-time location tracking library for React Native with Firebase synchronization.
 * Supports both TurboModules (new architecture) and Bridge (old architecture).
 *
 * @packageDocumentation
 */

import LiveTracking from './LiveTracking';

export default LiveTracking;
export { default as LiveTracking } from './LiveTracking';

// Re-export all types
export { TrackingState } from './types';
export type {
  OptimizationConfig,
  AndroidNotificationConfig,
  FirebaseConfig,
  TrackingConfig,
  LocationData,
  TrackingError,
  TrackingStatus,
  ConfigError,
  ConfigValidationResult,
  Subscription,
  LiveTrackingModule,
} from './types';

// Event listener functions
export { onLocationUpdate, onError, removeAllListeners } from './EventEmitter';

// Configuration validation utilities
export { validateConfig, applyDefaults } from './validation';

// Distance calculation utility
export { calculateDistance } from './utils/distance';

// Distance/Time Matrix filter
export { shouldAcceptLocation } from './filters/distanceTimeFilter';

// Location serialization for Firebase
export {
  serializeForCurrentPath,
  serializeForHistoryPath,
} from './serialization/locationSerializer';

// Retry/backoff utilities
export { calculateBackoffDelay, shouldRetry } from './utils/retry';
