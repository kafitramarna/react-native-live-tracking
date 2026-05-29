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

// ─── Validation Constants ────────────────────────────────────────────────────

const MAX_TARGETS = 20;
const MAX_PATH_LENGTH = 768;
const MAX_BATCH_SIZE = 1000;
const VALID_METHODS = ['set', 'push', 'update'] as const;

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
 * Note: Sync target defaults (batchSize, offlineQueue) are handled per-target
 * at the native layer. Targets pass through without modification here.
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
      service: config.firebase.service,
      targets: config.firebase.targets,
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

  // Detect deprecated fields
  validateDeprecatedFields(fb, errors);

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
      message: `firebase.service must be 'RTDB' or 'Firestore', got '${String(fb['service'])}'`,
      code: 'INVALID_VALUE',
    });
  }

  // Validate targets array
  validateTargets(fb['targets'], errors);
}

function validateDeprecatedFields(
  fb: Record<string, unknown>,
  errors: ConfigError[]
): void {
  if (fb['currentLocationPath'] !== undefined) {
    errors.push({
      field: 'firebase.currentLocationPath',
      message:
        'currentLocationPath is deprecated. Migrate to the targets array by adding a SyncTarget with method \'set\'',
      code: 'DEPRECATED_FIELD',
    });
  }

  if (fb['historyPath'] !== undefined) {
    errors.push({
      field: 'firebase.historyPath',
      message:
        'historyPath is deprecated. Migrate to the targets array by adding a SyncTarget with method \'push\'',
      code: 'DEPRECATED_FIELD',
    });
  }

  if (fb['historyBatchSize'] !== undefined) {
    errors.push({
      field: 'firebase.historyBatchSize',
      message:
        'historyBatchSize is deprecated. Migrate to per-target batchSize in the targets array',
      code: 'DEPRECATED_FIELD',
    });
  }
}

function validateTargets(targets: unknown, errors: ConfigError[]): void {
  if (targets === undefined || targets === null || !Array.isArray(targets)) {
    errors.push({
      field: 'firebase.targets',
      message: 'firebase.targets is required and must be an array',
      code: 'REQUIRED_FIELD',
    });
    return;
  }

  if (targets.length === 0) {
    errors.push({
      field: 'firebase.targets',
      message: 'firebase.targets must contain at least 1 sync target',
      code: 'INVALID_VALUE',
    });
    return;
  }

  if (targets.length > MAX_TARGETS) {
    errors.push({
      field: 'firebase.targets',
      message: `firebase.targets must contain at most ${MAX_TARGETS} sync targets, got ${targets.length}`,
      code: 'INVALID_VALUE',
    });
  }

  // Validate each target
  const seenPaths = new Set<string>();

  for (let i = 0; i < targets.length; i++) {
    const target = targets[i];
    const prefix = `firebase.targets[${i}]`;

    if (target === null || target === undefined || typeof target !== 'object') {
      errors.push({
        field: prefix,
        message: `${prefix} must be an object`,
        code: 'INVALID_TYPE',
      });
      continue;
    }

    const t = target as Record<string, unknown>;

    // Validate path
    validateTargetPath(t['path'], i, prefix, errors, seenPaths);

    // Validate method
    validateTargetMethod(t['method'], i, prefix, errors);

    // Validate batchSize (optional)
    if (t['batchSize'] !== undefined) {
      validateTargetBatchSize(t['batchSize'], i, prefix, errors);
    }

    // Validate offlineQueue (optional)
    if (t['offlineQueue'] !== undefined) {
      validateTargetOfflineQueue(t['offlineQueue'], i, prefix, errors);
    }
  }
}

function validateTargetPath(
  path: unknown,
  _index: number,
  prefix: string,
  errors: ConfigError[],
  seenPaths: Set<string>
): void {
  if (path === undefined || path === null || typeof path !== 'string') {
    errors.push({
      field: `${prefix}.path`,
      message: `${prefix}.path is required and must be a string`,
      code: 'REQUIRED_FIELD',
    });
    return;
  }

  if (path.trim().length === 0) {
    errors.push({
      field: `${prefix}.path`,
      message: `${prefix}.path must not be empty or whitespace-only`,
      code: 'INVALID_VALUE',
    });
    return;
  }

  if (path.length > MAX_PATH_LENGTH) {
    errors.push({
      field: `${prefix}.path`,
      message: `${prefix}.path must not exceed ${MAX_PATH_LENGTH} characters, got ${path.length}`,
      code: 'INVALID_VALUE',
    });
    return;
  }

  // Check for duplicate paths (case-sensitive)
  if (seenPaths.has(path)) {
    errors.push({
      field: `${prefix}.path`,
      message: `${prefix}.path '${path}' is a duplicate; each target must have a unique path`,
      code: 'DUPLICATE_VALUE',
    });
  } else {
    seenPaths.add(path);
  }
}

function validateTargetMethod(
  method: unknown,
  _index: number,
  prefix: string,
  errors: ConfigError[]
): void {
  if (method === undefined || method === null) {
    errors.push({
      field: `${prefix}.method`,
      message: `${prefix}.method is required and must be 'set', 'push', or 'update'`,
      code: 'REQUIRED_FIELD',
    });
    return;
  }

  if (
    typeof method !== 'string' ||
    !(VALID_METHODS as readonly string[]).includes(method)
  ) {
    errors.push({
      field: `${prefix}.method`,
      message: `${prefix}.method must be 'set', 'push', or 'update', got '${String(method)}'`,
      code: 'INVALID_VALUE',
    });
  }
}

function validateTargetBatchSize(
  batchSize: unknown,
  _index: number,
  prefix: string,
  errors: ConfigError[]
): void {
  if (
    typeof batchSize !== 'number' ||
    !isFinite(batchSize) ||
    !Number.isInteger(batchSize)
  ) {
    errors.push({
      field: `${prefix}.batchSize`,
      message: `${prefix}.batchSize must be a positive integer between 1 and ${MAX_BATCH_SIZE}`,
      code: 'INVALID_VALUE',
    });
    return;
  }

  if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
    errors.push({
      field: `${prefix}.batchSize`,
      message: `${prefix}.batchSize must be between 1 and ${MAX_BATCH_SIZE}, got ${batchSize}`,
      code: 'OUT_OF_RANGE',
    });
  }
}

function validateTargetOfflineQueue(
  offlineQueue: unknown,
  _index: number,
  prefix: string,
  errors: ConfigError[]
): void {
  if (typeof offlineQueue !== 'boolean') {
    errors.push({
      field: `${prefix}.offlineQueue`,
      message: `${prefix}.offlineQueue must be a boolean`,
      code: 'INVALID_TYPE',
    });
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
