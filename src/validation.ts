/**
 * Configuration validation and default value application for react-native-live-tracking.
 *
 * @packageDocumentation
 */

import type {
  ConfigError,
  ConfigValidationResult,
  TrackingConfig,
} from './types';

// ─── Default Values ──────────────────────────────────────────────────────────

const DEFAULT_INTERVAL_MS = 10000;
const DEFAULT_DISTANCE_FILTER_METERS = 10;
const DEFAULT_STOP_WHEN_STILL = true;
const DEFAULT_HISTORY_BATCH_SIZE = 15;

// ─── Validation ──────────────────────────────────────────────────────────────

/**
 * Validates a raw configuration object and returns a structured result
 * indicating whether the config is valid or contains errors.
 *
 * @param config - The raw configuration object to validate (unknown type for safety)
 * @returns A ConfigValidationResult with validity status and any errors found
 */
export function validateConfig(config: unknown): ConfigValidationResult {
  const errors: ConfigError[] = [];

  // Check that config is an object
  if (config === null || config === undefined || typeof config !== 'object') {
    errors.push({
      field: 'config',
      message: 'Configuration must be a non-null object',
      code: 'INVALID_TYPE',
    });
    return { valid: false, errors };
  }

  const cfg = config as Record<string, unknown>;

  // Validate optimization
  validateOptimization(cfg['optimization'], errors);

  // Validate firebase
  validateFirebase(cfg['firebase'], errors);

  // Validate androidNotification (optional)
  if (cfg['androidNotification'] !== undefined) {
    validateAndroidNotification(cfg['androidNotification'], errors);
  }

  return {
    valid: errors.length === 0,
    errors,
  };
}

/**
 * Applies default values to a valid TrackingConfig object.
 * Should only be called after validateConfig returns valid: true.
 *
 * @param config - A valid TrackingConfig object
 * @returns A new TrackingConfig with all defaults applied
 */
export function applyDefaults(config: TrackingConfig): TrackingConfig {
  return {
    ...config,
    optimization: {
      intervalMs: config.optimization.intervalMs ?? DEFAULT_INTERVAL_MS,
      distanceFilterMeters:
        config.optimization.distanceFilterMeters ?? DEFAULT_DISTANCE_FILTER_METERS,
      stopWhenStill:
        config.optimization.stopWhenStill ?? DEFAULT_STOP_WHEN_STILL,
    },
    firebase: {
      ...config.firebase,
      historyBatchSize:
        config.firebase.historyBatchSize ?? DEFAULT_HISTORY_BATCH_SIZE,
    },
  };
}

// ─── Internal Validation Helpers ─────────────────────────────────────────────

function validateOptimization(
  optimization: unknown,
  errors: ConfigError[]
): void {
  if (optimization === undefined || optimization === null) {
    errors.push({
      field: 'optimization',
      message: 'optimization is required and must be an object',
      code: 'REQUIRED_FIELD',
    });
    return;
  }

  if (typeof optimization !== 'object') {
    errors.push({
      field: 'optimization',
      message: 'optimization must be an object',
      code: 'INVALID_TYPE',
    });
    return;
  }

  const opt = optimization as Record<string, unknown>;

  // intervalMs (optional, but if provided must be positive number)
  if (opt['intervalMs'] !== undefined) {
    if (typeof opt['intervalMs'] !== 'number' || !isFinite(opt['intervalMs'] as number)) {
      errors.push({
        field: 'optimization.intervalMs',
        message: 'intervalMs must be a finite number',
        code: 'INVALID_TYPE',
      });
    } else if ((opt['intervalMs'] as number) <= 0) {
      errors.push({
        field: 'optimization.intervalMs',
        message: 'intervalMs must be greater than 0',
        code: 'OUT_OF_RANGE',
      });
    }
  }

  // distanceFilterMeters (optional, but if provided must be positive number)
  if (opt['distanceFilterMeters'] !== undefined) {
    if (
      typeof opt['distanceFilterMeters'] !== 'number' ||
      !isFinite(opt['distanceFilterMeters'] as number)
    ) {
      errors.push({
        field: 'optimization.distanceFilterMeters',
        message: 'distanceFilterMeters must be a finite number',
        code: 'INVALID_TYPE',
      });
    } else if ((opt['distanceFilterMeters'] as number) <= 0) {
      errors.push({
        field: 'optimization.distanceFilterMeters',
        message: 'distanceFilterMeters must be greater than 0',
        code: 'OUT_OF_RANGE',
      });
    }
  }

  // stopWhenStill (optional, but if provided must be boolean)
  if (opt['stopWhenStill'] !== undefined) {
    if (typeof opt['stopWhenStill'] !== 'boolean') {
      errors.push({
        field: 'optimization.stopWhenStill',
        message: 'stopWhenStill must be a boolean',
        code: 'INVALID_TYPE',
      });
    }
  }
}

function validateFirebase(firebase: unknown, errors: ConfigError[]): void {
  if (firebase === undefined || firebase === null) {
    errors.push({
      field: 'firebase',
      message: 'firebase is required and must be an object',
      code: 'REQUIRED_FIELD',
    });
    return;
  }

  if (typeof firebase !== 'object') {
    errors.push({
      field: 'firebase',
      message: 'firebase must be an object',
      code: 'INVALID_TYPE',
    });
    return;
  }

  const fb = firebase as Record<string, unknown>;

  // service (required, must be 'RTDB' or 'Firestore')
  if (fb['service'] === undefined || fb['service'] === null) {
    errors.push({
      field: 'firebase.service',
      message: "firebase.service is required and must be 'RTDB' or 'Firestore'",
      code: 'REQUIRED_FIELD',
    });
  } else if (fb['service'] !== 'RTDB' && fb['service'] !== 'Firestore') {
    errors.push({
      field: 'firebase.service',
      message: "firebase.service must be 'RTDB' or 'Firestore'",
      code: 'INVALID_VALUE',
    });
  }

  // At least one path must be provided
  const hasCurrentPath =
    typeof fb['currentLocationPath'] === 'string' &&
    (fb['currentLocationPath'] as string).trim().length > 0;
  const hasHistoryPath =
    typeof fb['historyPath'] === 'string' &&
    (fb['historyPath'] as string).trim().length > 0;

  if (!hasCurrentPath && !hasHistoryPath) {
    errors.push({
      field: 'firebase.currentLocationPath',
      message:
        'At least one of firebase.currentLocationPath or firebase.historyPath must be a non-empty string',
      code: 'REQUIRED_FIELD',
    });
  }

  // historyBatchSize (optional, but if provided must be a positive integer)
  if (fb['historyBatchSize'] !== undefined) {
    if (
      typeof fb['historyBatchSize'] !== 'number' ||
      !isFinite(fb['historyBatchSize'] as number)
    ) {
      errors.push({
        field: 'firebase.historyBatchSize',
        message: 'historyBatchSize must be a finite number',
        code: 'INVALID_TYPE',
      });
    } else if (
      (fb['historyBatchSize'] as number) <= 0 ||
      !Number.isInteger(fb['historyBatchSize'] as number)
    ) {
      errors.push({
        field: 'firebase.historyBatchSize',
        message: 'historyBatchSize must be a positive integer',
        code: 'OUT_OF_RANGE',
      });
    }
  }
}

function validateAndroidNotification(
  notification: unknown,
  errors: ConfigError[]
): void {
  if (typeof notification !== 'object' || notification === null) {
    errors.push({
      field: 'androidNotification',
      message: 'androidNotification must be an object',
      code: 'INVALID_TYPE',
    });
    return;
  }

  const notif = notification as Record<string, unknown>;

  // title (required, non-empty string)
  if (
    typeof notif['title'] !== 'string' ||
    (notif['title'] as string).trim().length === 0
  ) {
    errors.push({
      field: 'androidNotification.title',
      message: 'androidNotification.title must be a non-empty string',
      code: 'REQUIRED_FIELD',
    });
  }

  // text (required, non-empty string)
  if (
    typeof notif['text'] !== 'string' ||
    (notif['text'] as string).trim().length === 0
  ) {
    errors.push({
      field: 'androidNotification.text',
      message: 'androidNotification.text must be a non-empty string',
      code: 'REQUIRED_FIELD',
    });
  }
}
