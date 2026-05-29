/**
 * Property-based tests for sync target configuration validation.
 *
 * Uses fast-check to generate arbitrary configurations and verify
 * that the Config_Validator behaves correctly across the entire input space.
 *
 * @packageDocumentation
 */

import * as fc from 'fast-check';
import { validateConfig } from '../../src/validation';

// ─── Generators ──────────────────────────────────────────────────────────────

/** Generate a valid Firebase service value */
const validServiceArb = fc.constantFrom('RTDB', 'Firestore');

/** Generate a valid write method */
const validMethodArb = fc.constantFrom('set', 'push', 'update');

/** Generate a valid path (1–768 chars, not whitespace-only) */
const validPathArb = fc
  .tuple(
    fc.stringOf(
      fc.constantFrom(
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
        'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        '/', '-', '_', '.'
      ),
      { minLength: 0, maxLength: 100 }
    ),
    fc.nat({ max: 999 })
  )
  .map(([base, suffix]) => `/${base}/${suffix}`)
  .filter((p) => p.length >= 1 && p.length <= 768 && p.trim().length > 0);

/** Generate a valid batchSize (integer 1–1000) */
const validBatchSizeArb = fc.integer({ min: 1, max: 1000 });

/** Generate a valid offlineQueue (boolean) */
const validOfflineQueueArb = fc.boolean();

/** Generate a valid SyncTarget object */
const validSyncTargetArb = fc.record({
  path: validPathArb,
  method: validMethodArb,
  batchSize: fc.option(validBatchSizeArb, { nil: undefined }),
  offlineQueue: fc.option(validOfflineQueueArb, { nil: undefined }),
});

/** Generate a valid targets array with unique paths (1–20 elements) */
const validTargetsArb = fc
  .uniqueArray(validSyncTargetArb, {
    minLength: 1,
    maxLength: 20,
    selector: (t) => t.path,
  })
  .filter((arr) => arr.length >= 1);

/** Generate a valid firebase config object */
const validFirebaseConfigArb = fc.record({
  service: validServiceArb,
  targets: validTargetsArb,
});

/** Generate a valid full config (with optimization) */
const validFullConfigArb = fc.record({
  optimization: fc.record({
    intervalMs: fc.option(fc.integer({ min: 1, max: 60000 }), { nil: undefined }),
    distanceFilterMeters: fc.option(fc.integer({ min: 1, max: 1000 }), { nil: undefined }),
    stopWhenStill: fc.option(fc.boolean(), { nil: undefined }),
  }),
  firebase: validFirebaseConfigArb,
});

/** Generate an invalid method value */
const invalidMethodArb = fc
  .string({ minLength: 1, maxLength: 20 })
  .filter((s) => s !== 'set' && s !== 'push' && s !== 'update');

/** Generate an invalid path (empty or whitespace-only) */
const invalidPathArb = fc.constantFrom('', '   ', '\t', '\n', '  \t  ');

/** Generate an invalid batchSize */
const invalidBatchSizeArb = fc.oneof(
  fc.integer({ min: -1000, max: 0 }),       // non-positive
  fc.double({ min: 0.1, max: 999.9, noNaN: true }).filter((n) => !Number.isInteger(n)), // non-integer
  fc.integer({ min: 1001, max: 10000 }),    // exceeds max
  fc.constant(NaN),                          // NaN
  fc.constant(Infinity),                     // Infinity
);

/** Generate an invalid offlineQueue (non-boolean) */
const invalidOfflineQueueArb = fc.oneof(
  fc.string({ minLength: 1, maxLength: 10 }),
  fc.integer(),
  fc.constant(null),
  fc.constant(0),
  fc.constant(1),
);

// ─── Property Tests ──────────────────────────────────────────────────────────

describe('Feature: flexible-firebase-sync, Property 1: Valid configuration acceptance', () => {
  /**
   * **Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5**
   *
   * For any firebase configuration object with valid service, valid targets
   * (1–20 with unique paths, valid methods, optional valid batchSize/offlineQueue),
   * the Config_Validator SHALL return { valid: true, errors: [] }.
   */
  it('accepts any valid configuration', () => {
    fc.assert(
      fc.property(validFullConfigArb, (config) => {
        const result = validateConfig(config);
        expect(result.valid).toBe(true);
        expect(result.errors).toHaveLength(0);
      }),
      { numRuns: 200 }
    );
  });
});

describe('Feature: flexible-firebase-sync, Property 2: Invalid fields produce structured errors with target index', () => {
  /**
   * **Validates: Requirements 1.2, 1.3, 1.4, 1.5, 6.1, 6.2, 6.3, 6.7**
   *
   * For any SyncTarget at index i that contains an invalid field,
   * the Config_Validator SHALL return an error whose field property
   * contains the target index i and the invalid field name.
   */
  it('invalid path produces error with correct target index', () => {
    fc.assert(
      fc.property(
        validServiceArb,
        fc.integer({ min: 0, max: 4 }),
        invalidPathArb,
        (service, targetIndex, invalidPath) => {
          // Build a targets array with the invalid target at the specified index
          const targets: Array<{ path: string; method: string }> = [];
          for (let i = 0; i <= targetIndex; i++) {
            if (i === targetIndex) {
              targets.push({ path: invalidPath, method: 'set' });
            } else {
              targets.push({ path: `/valid/path/${i}`, method: 'set' });
            }
          }

          const config = {
            optimization: {},
            firebase: { service, targets },
          };

          const result = validateConfig(config);
          expect(result.valid).toBe(false);
          expect(
            result.errors.some(
              (e) => e.field === `firebase.targets[${targetIndex}].path`
            )
          ).toBe(true);
        }
      ),
      { numRuns: 100 }
    );
  });

  it('invalid method produces error with correct target index', () => {
    fc.assert(
      fc.property(
        validServiceArb,
        fc.integer({ min: 0, max: 4 }),
        invalidMethodArb,
        (service, targetIndex, invalidMethod) => {
          const targets: Array<{ path: string; method: string }> = [];
          for (let i = 0; i <= targetIndex; i++) {
            if (i === targetIndex) {
              targets.push({ path: `/path/${targetIndex}`, method: invalidMethod });
            } else {
              targets.push({ path: `/valid/path/${i}`, method: 'set' });
            }
          }

          const config = {
            optimization: {},
            firebase: { service, targets },
          };

          const result = validateConfig(config);
          expect(result.valid).toBe(false);
          expect(
            result.errors.some(
              (e) => e.field === `firebase.targets[${targetIndex}].method`
            )
          ).toBe(true);
        }
      ),
      { numRuns: 100 }
    );
  });

  it('invalid batchSize produces error with correct target index', () => {
    fc.assert(
      fc.property(
        validServiceArb,
        fc.integer({ min: 0, max: 4 }),
        invalidBatchSizeArb,
        (service, targetIndex, invalidBatch) => {
          const targets: Array<{ path: string; method: string; batchSize?: unknown }> = [];
          for (let i = 0; i <= targetIndex; i++) {
            if (i === targetIndex) {
              targets.push({ path: `/path/${targetIndex}`, method: 'set', batchSize: invalidBatch });
            } else {
              targets.push({ path: `/valid/path/${i}`, method: 'set' });
            }
          }

          const config = {
            optimization: {},
            firebase: { service, targets },
          };

          const result = validateConfig(config);
          expect(result.valid).toBe(false);
          expect(
            result.errors.some(
              (e) => e.field === `firebase.targets[${targetIndex}].batchSize`
            )
          ).toBe(true);
        }
      ),
      { numRuns: 100 }
    );
  });

  it('invalid offlineQueue produces error with correct target index', () => {
    fc.assert(
      fc.property(
        validServiceArb,
        fc.integer({ min: 0, max: 4 }),
        invalidOfflineQueueArb,
        (service, targetIndex, invalidQueue) => {
          const targets: Array<{ path: string; method: string; offlineQueue?: unknown }> = [];
          for (let i = 0; i <= targetIndex; i++) {
            if (i === targetIndex) {
              targets.push({ path: `/path/${targetIndex}`, method: 'set', offlineQueue: invalidQueue });
            } else {
              targets.push({ path: `/valid/path/${i}`, method: 'set' });
            }
          }

          const config = {
            optimization: {},
            firebase: { service, targets },
          };

          const result = validateConfig(config);
          expect(result.valid).toBe(false);
          expect(
            result.errors.some(
              (e) => e.field === `firebase.targets[${targetIndex}].offlineQueue`
            )
          ).toBe(true);
        }
      ),
      { numRuns: 100 }
    );
  });
});

describe('Feature: flexible-firebase-sync, Property 3: All validation errors collected without short-circuit', () => {
  /**
   * **Validates: Requirements 1.9, 6.6, 6.8**
   *
   * For any configuration containing N distinct validation violations across
   * multiple targets, the Config_Validator SHALL return valid: false and
   * errors.length >= N. When valid is true, errors SHALL be empty.
   */
  it('collects all errors from multiple targets without short-circuiting', () => {
    fc.assert(
      fc.property(
        validServiceArb,
        fc.integer({ min: 2, max: 5 }),
        (service, numTargets) => {
          // Each target has exactly one violation (invalid method)
          const targets = Array.from({ length: numTargets }, (_, i) => ({
            path: `/path/${i}`,
            method: `invalid_${i}`,
          }));

          const config = {
            optimization: {},
            firebase: { service, targets },
          };

          const result = validateConfig(config);
          expect(result.valid).toBe(false);
          // Should have at least one error per target
          expect(result.errors.length).toBeGreaterThanOrEqual(numTargets);
        }
      ),
      { numRuns: 100 }
    );
  });

  it('when valid is true, errors array is empty', () => {
    fc.assert(
      fc.property(validFullConfigArb, (config) => {
        const result = validateConfig(config);
        if (result.valid) {
          expect(result.errors).toHaveLength(0);
        } else {
          expect(result.errors.length).toBeGreaterThan(0);
        }
      }),
      { numRuns: 200 }
    );
  });

  it('multiple violations per target are all reported', () => {
    fc.assert(
      fc.property(
        validServiceArb,
        fc.integer({ min: 1, max: 3 }),
        (service, numTargets) => {
          // Each target has 2 violations: invalid path AND invalid method
          const targets = Array.from({ length: numTargets }, (_, i) => ({
            path: '',
            method: `bad_${i}`,
          }));

          const config = {
            optimization: {},
            firebase: { service, targets },
          };

          const result = validateConfig(config);
          expect(result.valid).toBe(false);
          // At least 2 errors per target (path + method)
          expect(result.errors.length).toBeGreaterThanOrEqual(numTargets * 2);
        }
      ),
      { numRuns: 100 }
    );
  });
});

describe('Feature: flexible-firebase-sync, Property 4: Duplicate path rejection', () => {
  /**
   * **Validates: Requirements 1.8**
   *
   * For any configuration where two or more SyncTargets share the same path
   * (case-sensitive), the Config_Validator SHALL return valid: false with at
   * least one error identifying the duplicate.
   */
  it('rejects configurations with duplicate paths', () => {
    fc.assert(
      fc.property(
        validServiceArb,
        validPathArb,
        validMethodArb,
        validMethodArb,
        fc.integer({ min: 0, max: 3 }),
        (service, duplicatePath, method1, method2, extraTargets) => {
          // Build targets with at least two sharing the same path
          const targets: Array<{ path: string; method: string }> = [];

          // Add some unique targets before
          for (let i = 0; i < extraTargets; i++) {
            targets.push({ path: `/unique/prefix/${i}`, method: 'set' });
          }

          // Add two targets with the same path
          targets.push({ path: duplicatePath, method: method1 });
          targets.push({ path: duplicatePath, method: method2 });

          const config = {
            optimization: {},
            firebase: { service, targets },
          };

          const result = validateConfig(config);
          expect(result.valid).toBe(false);
          expect(
            result.errors.some(
              (e) => e.code === 'DUPLICATE_VALUE'
            )
          ).toBe(true);
        }
      ),
      { numRuns: 100 }
    );
  });

  it('paths differing only in case are NOT considered duplicates', () => {
    fc.assert(
      fc.property(
        validServiceArb,
        validPathArb,
        (service, basePath) => {
          // Create two paths that differ in case
          const path1 = basePath.toLowerCase() + '/a';
          const path2 = basePath.toUpperCase() + '/a';

          // Skip if they happen to be the same (e.g., all numeric paths)
          fc.pre(path1 !== path2);

          const config = {
            optimization: {},
            firebase: {
              service,
              targets: [
                { path: path1, method: 'set' as const },
                { path: path2, method: 'push' as const },
              ],
            },
          };

          const result = validateConfig(config);
          // Should not have duplicate errors (may have other errors if paths are invalid)
          expect(
            result.errors.some((e) => e.code === 'DUPLICATE_VALUE')
          ).toBe(false);
        }
      ),
      { numRuns: 100 }
    );
  });
});

describe('Feature: flexible-firebase-sync, Property 5: Deprecated field rejection', () => {
  /**
   * **Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.6**
   *
   * For any configuration containing a defined currentLocationPath, historyPath,
   * or historyBatchSize field within the firebase object, the Config_Validator
   * SHALL return valid: false with an error using code DEPRECATED_FIELD for each
   * deprecated field present, regardless of whether a valid targets array also exists.
   */
  it('rejects configs with currentLocationPath and reports DEPRECATED_FIELD', () => {
    fc.assert(
      fc.property(
        validFullConfigArb,
        fc.string({ minLength: 1, maxLength: 50 }),
        (validConfig, deprecatedValue) => {
          const config = {
            ...validConfig,
            firebase: {
              ...validConfig.firebase,
              currentLocationPath: deprecatedValue,
            },
          };

          const result = validateConfig(config);
          expect(result.valid).toBe(false);
          expect(
            result.errors.some(
              (e) =>
                e.field === 'firebase.currentLocationPath' &&
                e.code === 'DEPRECATED_FIELD'
            )
          ).toBe(true);
        }
      ),
      { numRuns: 100 }
    );
  });

  it('rejects configs with historyPath and reports DEPRECATED_FIELD', () => {
    fc.assert(
      fc.property(
        validFullConfigArb,
        fc.string({ minLength: 1, maxLength: 50 }),
        (validConfig, deprecatedValue) => {
          const config = {
            ...validConfig,
            firebase: {
              ...validConfig.firebase,
              historyPath: deprecatedValue,
            },
          };

          const result = validateConfig(config);
          expect(result.valid).toBe(false);
          expect(
            result.errors.some(
              (e) =>
                e.field === 'firebase.historyPath' &&
                e.code === 'DEPRECATED_FIELD'
            )
          ).toBe(true);
        }
      ),
      { numRuns: 100 }
    );
  });

  it('rejects configs with historyBatchSize and reports DEPRECATED_FIELD', () => {
    fc.assert(
      fc.property(
        validFullConfigArb,
        fc.integer({ min: 1, max: 100 }),
        (validConfig, deprecatedValue) => {
          const config = {
            ...validConfig,
            firebase: {
              ...validConfig.firebase,
              historyBatchSize: deprecatedValue,
            },
          };

          const result = validateConfig(config);
          expect(result.valid).toBe(false);
          expect(
            result.errors.some(
              (e) =>
                e.field === 'firebase.historyBatchSize' &&
                e.code === 'DEPRECATED_FIELD'
            )
          ).toBe(true);
        }
      ),
      { numRuns: 100 }
    );
  });

  it('reports one DEPRECATED_FIELD error per deprecated field present', () => {
    fc.assert(
      fc.property(
        validFullConfigArb,
        fc.subarray(['currentLocationPath', 'historyPath', 'historyBatchSize'], {
          minLength: 1,
          maxLength: 3,
        }),
        (validConfig, deprecatedFields) => {
          const firebase: Record<string, unknown> = {
            ...validConfig.firebase,
          };

          for (const field of deprecatedFields) {
            firebase[field] = field === 'historyBatchSize' ? 10 : '/some/path';
          }

          const config = {
            ...validConfig,
            firebase,
          };

          const result = validateConfig(config);
          expect(result.valid).toBe(false);

          const deprecatedErrors = result.errors.filter(
            (e) => e.code === 'DEPRECATED_FIELD'
          );
          expect(deprecatedErrors.length).toBe(deprecatedFields.length);

          for (const field of deprecatedFields) {
            expect(
              deprecatedErrors.some((e) => e.field === `firebase.${field}`)
            ).toBe(true);
          }
        }
      ),
      { numRuns: 100 }
    );
  });
});
