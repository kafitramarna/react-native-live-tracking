/**
 * TypeScript type definitions for react-native-live-tracking.
 *
 * @packageDocumentation
 */

// ─── Enums ───────────────────────────────────────────────────────────────────

/**
 * Internal state machine for tracking lifecycle.
 */
export enum TrackingState {
  IDLE = 'idle',
  CONFIGURED = 'configured',
  TRACKING = 'tracking',
  MOTION_SLEEP = 'motion_sleep',
  PAUSED_GPS = 'paused_gps',
}

// ─── Configuration Interfaces ────────────────────────────────────────────────

/**
 * Strategy used by the distance/time filter.
 * - 'interval': accept when enough time has elapsed
 * - 'distance': accept when enough distance has been covered
 * - 'both': accept only when both conditions are met (default)
 */
export type OptimizationMode = 'interval' | 'distance' | 'both';

/**
 * Optimization parameters for location tracking.
 */
export interface OptimizationConfig {
  /** Minimum time interval between location updates in milliseconds. Default: 10000 */
  intervalMs?: number;
  /** Minimum distance change in meters to trigger an update. Default: 10 */
  distanceFilterMeters?: number;
  /** Whether to reduce GPS accuracy when device is still. Default: true */
  stopWhenStill?: boolean;
  /** Filter strategy: 'interval', 'distance', or 'both'. Default: 'both' */
  mode?: OptimizationMode;
}

/**
 * Android foreground service notification configuration.
 */
export interface AndroidNotificationConfig {
  /** Whether to show the foreground service notification. Default: true */
  enabled?: boolean;
  /** Notification title */
  title: string;
  /** Notification body text */
  text: string;
  /** Notification icon resource name */
  icon?: string;
  /** Notification channel ID */
  channelId?: string;
  /** Notification channel name */
  channelName?: string;
}

/**
 * iOS persistent notification configuration.
 * Shows a local notification while tracking is active (similar to Android's foreground service notification).
 */
export interface IOSNotificationConfig {
  /** Whether to show the persistent local notification. Default: true */
  enabled?: boolean;
  /** Notification title */
  title: string;
  /** Notification body text */
  text: string;
}

/**
 * A user-defined sync target specifying a Firebase path, write method,
 * and optional batching/offline queue settings.
 */
export interface SyncTarget {
  /** Firebase path to write to */
  path: string;
  /** Write method: 'set' (overwrite), 'push' (append), 'update' (merge) */
  method: 'set' | 'push' | 'update';
  /** Number of points to accumulate before writing. Default: 1 (immediate) */
  batchSize?: number;
  /** Whether to persist data offline when device has no connectivity */
  offlineQueue?: boolean;
}

/**
 * Firebase connection and sync target configuration.
 */
export interface FirebaseConfig {
  /** Firebase service type: Realtime Database or Firestore */
  service: 'RTDB' | 'Firestore';
  /** Array of sync targets (at least one required) */
  targets: [SyncTarget, ...SyncTarget[]];
}

/**
 * Android platform behavior configuration.
 */
export interface AndroidConfig {
  /**
   * Whether the tracking foreground service holds a partial wake lock so the
   * OS cannot suspend the process while tracking is active. Default: true.
   * Note: deep Doze ignores wake locks for non-exempted apps.
   */
  wakeLock?: boolean;
}

/**
 * Native lifecycle control configuration.
 *
 * When provided, the native layer arms a status watcher on the given RTDB
 * path while tracking is active — remote stop signals are honored even when
 * the RN/JS layer is dead. The native layer can also self-heal: rebuild
 * engines from the persisted config after process death/reboot and resume
 * tracking without the JS layer.
 */
export interface ControlConfig {
  /**
   * RTDB path to watch for a stop signal (e.g. 'delivery/123/status').
   * When omitted, the path is derived from the first RTDB sync target
   * (e.g. 'delivery/123/current' -> 'delivery/123/status').
   */
  statusPath?: string;
  /**
   * Numeric value on statusPath that triggers a native stop. Default: 2
   */
  stopValue?: number;
  /**
   * Whether engines may be rebuilt and resumed natively after process
   * death/reboot. Default: true
   */
  resumeOnRestart?: boolean;
}

/**
 * Main configuration object passed to `configure()`.
 */
export interface TrackingConfig {
  /** Optimization settings for battery and update frequency */
  optimization: OptimizationConfig;
  /** Android platform behavior settings (Android only) */
  android?: AndroidConfig;
  /** Android foreground service notification settings (Android only) */
  androidNotification?: AndroidNotificationConfig;
  /** iOS persistent notification settings (iOS only) */
  iosNotification?: IOSNotificationConfig;
  /** Firebase connection and path configuration */
  firebase: FirebaseConfig;
  /** Native lifecycle control (status watcher + native resume) */
  control?: ControlConfig;
}

// ─── Data Interfaces ─────────────────────────────────────────────────────────

/**
 * Location data emitted by the library on each valid location update.
 */
export interface LocationData {
  /** Latitude in degrees (-90 to 90) */
  latitude: number;
  /** Longitude in degrees (-180 to 180) */
  longitude: number;
  /** Unix timestamp in milliseconds */
  timestamp: number;
  /** Horizontal accuracy in meters */
  accuracy: number;
  /** Speed in meters per second, or null if unavailable */
  speed: number | null;
  /** Altitude in meters above sea level, or null if unavailable */
  altitude: number | null;
  /** Bearing/heading in degrees (0-360), or null if unavailable */
  bearing: number | null;
}

// ─── Error & Status Interfaces ───────────────────────────────────────────────

/**
 * Error object emitted by the library when tracking issues occur.
 */
export interface TrackingError {
  /** Error code identifier (e.g., 'PERMISSION_DENIED', 'GPS_DISABLED') */
  code: string;
  /** Human-readable error message */
  message: string;
  /** Whether the error is recoverable (auto-retry or user action can fix) */
  recoverable: boolean;
  /** Additional error context */
  details?: Record<string, unknown>;
}

/**
 * Current tracking status snapshot.
 */
export interface TrackingStatus {
  /** Current state of the tracking lifecycle */
  state: TrackingState;
  /** Whether the device currently has network connectivity */
  isOnline: boolean;
  /** Number of locations waiting in the offline queue */
  queuedLocations: number;
  /** Last known location, or null if no location has been received */
  lastLocation: LocationData | null;
  /** Current battery optimization mode */
  batteryOptimization: 'full_accuracy' | 'low_power' | 'disabled';
  /** Whether the native status watcher is currently armed (Android) */
  nativeWatching: boolean;
  /**
   * Whether the app is exempted from battery optimization (Android only).
   * Omitted on iOS where the concept does not exist.
   */
  batteryOptimizationExempt?: boolean;
  /**
   * Whether the foreground service is configured to hold a wake lock
   * (Android only).
   */
  wakeLock?: boolean;
  /**
   * Wall-clock time (ms) of the last accepted location fix, or null before
   * the first fix. Use this — not lastLocation.timestamp — to judge liveness.
   */
  lastFixAt?: number | null;
  /**
   * How many times the native stale-location watchdog had to re-request
   * location updates this session (Android and iOS).
   */
  staleRecoveries?: number;
  /**
   * Why the foreground service was last restarted outside the normal flow
   * (e.g. 'sticky_restart', 'boot', 'service_destroyed_while_tracking').
   * One-shot marker: cleared after the first getStatus() that reads it.
   */
  lastRestartReason: string | null;
  /**
   * Why tracking last stopped (e.g. 'manual', 'remote_status',
   * 'permission_revoked', 'resume_failed', 'resume_disabled').
   * One-shot marker: cleared after the first getStatus() that reads it.
   */
  lastStopReason: string | null;
}

// ─── Validation Interfaces ───────────────────────────────────────────────────

/**
 * Single configuration validation error.
 */
export interface ConfigError {
  /** Dot-notation path to the invalid field (e.g., 'firebase.service') */
  field: string;
  /** Human-readable description of the validation error */
  message: string;
  /** Machine-readable error code */
  code: string;
}

/**
 * Result of configuration validation.
 */
export interface ConfigValidationResult {
  /** Whether the configuration is valid */
  valid: boolean;
  /** List of validation errors (empty if valid) */
  errors: ConfigError[];
}

// ─── Utility Types ───────────────────────────────────────────────────────────

/**
 * Subscription handle returned by event listener registrations.
 * Call `remove()` to unsubscribe from the event.
 */
export interface Subscription {
  /** Unsubscribe from the event */
  remove(): void;
}

// ─── Module Interface ────────────────────────────────────────────────────────

/**
 * Main public API interface for the react-native-live-tracking library.
 */
export interface LiveTrackingModule {
  /**
   * Configure the tracking library with the given parameters.
   * Must be called before `start()`.
   */
  configure(config: TrackingConfig): Promise<void>;

  /**
   * Start location tracking. Requires `configure()` to have been called first.
   */
  start(): Promise<void>;

  /**
   * Stop location tracking and clean up resources.
   */
  stop(): Promise<void>;

  /**
   * Register a callback for location updates.
   * Returns a Subscription that can be used to unsubscribe.
   */
  onLocationUpdate(callback: (location: LocationData) => void): Subscription;

  /**
   * Register a callback for tracking errors.
   * Returns a Subscription that can be used to unsubscribe.
   */
  onError(callback: (error: TrackingError) => void): Subscription;

  /**
   * Get the current tracking status.
   */
  getStatus(): Promise<TrackingStatus>;

  /**
   * Get the number of locations currently queued for sync.
   */
  getQueuedLocations(): Promise<number>;

  /**
   * Get the number of queued locations per target path.
   * Returns a record mapping each configured target path to its queued location count.
   */
  getQueuedLocationsByTarget(): Promise<Record<string, number>>;

  /**
   * Whether the app is exempted from battery optimization (Doze whitelist).
   * Always resolves true on iOS.
   */
  isIgnoringBatteryOptimizations(): Promise<boolean>;

  /**
   * Open the system screen where the user can exempt the app from battery
   * optimization. No-op on iOS.
   */
  openBatteryOptimizationSettings(): Promise<void>;
}
