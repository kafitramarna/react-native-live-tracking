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
}

// ─── Configuration Interfaces ────────────────────────────────────────────────

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
}

/**
 * Android foreground service notification configuration.
 */
export interface AndroidNotificationConfig {
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
 * Firebase connection and path configuration.
 */
export interface FirebaseConfig {
  /** Firebase service type: Realtime Database or Firestore */
  service: 'RTDB' | 'Firestore';
  /** Path for current location (overwrite mode). At least one path must be configured. */
  currentLocationPath?: string;
  /** Path for location history (append mode). At least one path must be configured. */
  historyPath?: string;
  /** Number of locations to batch before sending to history path. Default: 15 */
  historyBatchSize?: number;
}

/**
 * Main configuration object passed to `configure()`.
 */
export interface TrackingConfig {
  /** Optimization settings for battery and update frequency */
  optimization: OptimizationConfig;
  /** Android foreground service notification settings (Android only) */
  androidNotification?: AndroidNotificationConfig;
  /** Firebase connection and path configuration */
  firebase: FirebaseConfig;
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
}
