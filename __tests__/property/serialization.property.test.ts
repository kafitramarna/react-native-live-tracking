/**
 * Property-based tests for serialization logic.
 *
 * Uses fast-check to verify that configuration serialization preserves
 * defined fields, omits undefined fields, and that location serialization
 * never produces placeholder values for unavailable sensor fields.
 *
 * @packageDocumentation
 */

import * as fc from 'fast-check';
import { serializeLocationForTarget } from '../../src/serialization/locationSerializer';
import type { LocationData } from '../../src/types';

// ─── Generators ──────────────────────────────────────────────────────────────

/** Generate a valid Firebase service value */
const validServiceArb = fc.constantFrom('RTDB' as const, 'Firestore' as const);

/** Generate a valid write method */
const validMethodArb = fc.constantFrom('set' as const, 'push' as const, 'update' as const);

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

/** Generate a valid SyncTarget with explicit control over optional fields */
const validSyncTargetWithOptionalsArb = fc.record({
  path: validPathArb,
  method: validMethodArb,
  batchSize: fc.option(validBatchSizeArb, { nil: undefined }),
  offlineQueue: fc.option(validOfflineQueueArb, { nil: undefined }),
});

/** Generate a valid targets array with unique paths (1–5 elements for perf) */
const validTargetsArb = fc
  .uniqueArray(validSyncTargetWithOptionalsArb, {
    minLength: 1,
    maxLength: 5,
    selector: (t) => t.path,
  })
  .filter((arr) => arr.length >= 1);

/** Generate a valid full TrackingConfig */
const validFullConfigArb = fc.record({
  optimization: fc.record({
    intervalMs: fc.option(fc.integer({ min: 1, max: 60000 }), { nil: undefined }),
    distanceFilterMeters: fc.option(fc.integer({ min: 1, max: 1000 }), { nil: undefined }),
    stopWhenStill: fc.option(fc.boolean(), { nil: undefined }),
  }),
  firebase: fc.record({
    service: validServiceArb,
    targets: validTargetsArb,
  }),
});

/** Generate a valid latitude (-90 to 90) */
const latitudeArb = fc.double({ min: -90, max: 90, noNaN: true });

/** Generate a valid longitude (-180 to 180) */
const longitudeArb = fc.double({ min: -180, max: 180, noNaN: true });

/** Generate a valid timestamp (positive integer) */
const timestampArb = fc.integer({ min: 1000000000000, max: 2000000000000 });

/** Generate a valid accuracy (positive number) */
const accuracyArb = fc.double({ min: 0.1, max: 1000, noNaN: true });

/** Generate a valid speed (non-negative) or null */
const speedArb = fc.option(
  fc.double({ min: 0, max: 200, noNaN: true }),
  { nil: null }
);

/** Generate a valid altitude or null */
const altitudeArb = fc.option(
  fc.double({ min: -500, max: 50000, noNaN: true }),
  { nil: null }
);

/** Generate a valid bearing (0-360) or null */
const bearingArb = fc.option(
  fc.double({ min: 0, max: 360, noNaN: true }),
  { nil: null }
);

/** Generate a valid LocationData object */
const locationDataArb = fc.record({
  latitude: latitudeArb,
  longitude: longitudeArb,
  timestamp: timestampArb,
  accuracy: accuracyArb,
  speed: speedArb,
  altitude: altitudeArb,
  bearing: bearingArb,
});

/** Generate a LocationData with all optional sensor fields null */
const locationWithNullSensorsArb = fc.record({
  latitude: latitudeArb,
  longitude: longitudeArb,
  timestamp: timestampArb,
  accuracy: accuracyArb,
  speed: fc.constant(null as null),
  altitude: fc.constant(null as null),
  bearing: fc.constant(null as null),
});

// ─── Property Tests ──────────────────────────────────────────────────────────

describe('Feature: flexible-firebase-sync, Property 6: Configuration serialization preserves defined fields and omits undefined', () => {
  /**
   * **Validates: Requirements 9.1**
   *
   * For any valid TrackingConfig object, serializing it to JSON and parsing
   * the result back SHALL produce an object where every defined field in the
   * original config is present with the same value, and no field that was
   * undefined in the original appears in the JSON output.
   */
  it('serialized JSON preserves all defined fields with same values', () => {
    fc.assert(
      fc.property(validFullConfigArb, (config) => {
        const jsonString = JSON.stringify(config);
        const parsed = JSON.parse(jsonString);

        // firebase.service is preserved
        expect(parsed.firebase.service).toBe(config.firebase.service);

        // firebase.targets array length is preserved
        expect(parsed.firebase.targets.length).toBe(config.firebase.targets.length);

        // Each target's defined fields are preserved
        for (let i = 0; i < config.firebase.targets.length; i++) {
          const original = config.firebase.targets[i]!;
          const restored = parsed.firebase.targets[i];

          // Required fields always present
          expect(restored.path).toBe(original.path);
          expect(restored.method).toBe(original.method);

          // Optional fields: if defined, must be preserved with same value
          if (original.batchSize !== undefined) {
            expect(restored.batchSize).toBe(original.batchSize);
          }
          if (original.offlineQueue !== undefined) {
            expect(restored.offlineQueue).toBe(original.offlineQueue);
          }
        }

        // optimization fields preserved when defined
        if (config.optimization.intervalMs !== undefined) {
          expect(parsed.optimization.intervalMs).toBe(config.optimization.intervalMs);
        }
        if (config.optimization.distanceFilterMeters !== undefined) {
          expect(parsed.optimization.distanceFilterMeters).toBe(
            config.optimization.distanceFilterMeters
          );
        }
        if (config.optimization.stopWhenStill !== undefined) {
          expect(parsed.optimization.stopWhenStill).toBe(config.optimization.stopWhenStill);
        }
      }),
      { numRuns: 200 }
    );
  });

  it('serialized JSON omits fields that were undefined in the original', () => {
    fc.assert(
      fc.property(validFullConfigArb, (config) => {
        const jsonString = JSON.stringify(config);
        const parsed = JSON.parse(jsonString);

        // Check each target for undefined fields not appearing in output
        for (let i = 0; i < config.firebase.targets.length; i++) {
          const original = config.firebase.targets[i]!;
          const restored = parsed.firebase.targets[i];

          if (original.batchSize === undefined) {
            expect(restored).not.toHaveProperty('batchSize');
          }
          if (original.offlineQueue === undefined) {
            expect(restored).not.toHaveProperty('offlineQueue');
          }
        }

        // Check optimization fields
        if (config.optimization.intervalMs === undefined) {
          expect(parsed.optimization).not.toHaveProperty('intervalMs');
        }
        if (config.optimization.distanceFilterMeters === undefined) {
          expect(parsed.optimization).not.toHaveProperty('distanceFilterMeters');
        }
        if (config.optimization.stopWhenStill === undefined) {
          expect(parsed.optimization).not.toHaveProperty('stopWhenStill');
        }
      }),
      { numRuns: 200 }
    );
  });

  it('JSON.stringify + JSON.parse round-trip produces equivalent config', () => {
    fc.assert(
      fc.property(validFullConfigArb, (config) => {
        const jsonString = JSON.stringify(config);
        const parsed = JSON.parse(jsonString);

        // Re-serialize and compare: should be identical
        const reSerializedString = JSON.stringify(parsed);
        expect(reSerializedString).toBe(jsonString);
      }),
      { numRuns: 200 }
    );
  });
});

describe('Feature: flexible-firebase-sync, Property 16: Location serialization excludes unavailable sensor fields', () => {
  /**
   * **Validates: Requirements 3.7**
   *
   * For any location data point where optional fields (speed, heading/bearing,
   * altitude) are unavailable from the device sensor, the serialized write
   * payload SHALL either omit those fields entirely or include them as null,
   * and SHALL never contain default placeholder values (e.g., 0, -1, "unknown").
   */
  it('null sensor fields serialize as null, never as placeholder values', () => {
    fc.assert(
      fc.property(locationWithNullSensorsArb, (location: LocationData) => {
        const payload = serializeLocationForTarget(location);

        // speed, heading, altitude must be null (not 0, -1, or any other placeholder)
        expect(payload.speed).toBeNull();
        expect(payload.heading).toBeNull();
        expect(payload.altitude).toBeNull();
      }),
      { numRuns: 200 }
    );
  });

  it('serialized payload with null sensors never contains placeholder values (0, -1, "unknown")', () => {
    fc.assert(
      fc.property(locationWithNullSensorsArb, (location: LocationData) => {
        const payload = serializeLocationForTarget(location);
        const jsonString = JSON.stringify(payload);
        const parsed = JSON.parse(jsonString);

        // The optional fields in the JSON output must be null, not 0 or -1
        expect(parsed.speed).toBeNull();
        expect(parsed.heading).toBeNull();
        expect(parsed.altitude).toBeNull();

        // Ensure no "unknown" string values
        expect(parsed.speed).not.toBe('unknown');
        expect(parsed.heading).not.toBe('unknown');
        expect(parsed.altitude).not.toBe('unknown');
      }),
      { numRuns: 200 }
    );
  });

  it('available sensor fields are preserved with their actual values', () => {
    fc.assert(
      fc.property(locationDataArb, (location: LocationData) => {
        const payload = serializeLocationForTarget(location);

        // Required fields always preserved
        expect(payload.latitude).toBe(location.latitude);
        expect(payload.longitude).toBe(location.longitude);
        expect(payload.timestamp).toBe(location.timestamp);
        expect(payload.accuracy).toBe(location.accuracy);

        // Optional fields: if available (non-null), must match original value
        if (location.speed !== null) {
          expect(payload.speed).toBe(location.speed);
        } else {
          expect(payload.speed).toBeNull();
        }

        if (location.bearing !== null) {
          expect(payload.heading).toBe(location.bearing);
        } else {
          expect(payload.heading).toBeNull();
        }

        if (location.altitude !== null) {
          expect(payload.altitude).toBe(location.altitude);
        } else {
          expect(payload.altitude).toBeNull();
        }
      }),
      { numRuns: 200 }
    );
  });

  it('serialized location JSON never contains -1 as a sensor field value when sensor is unavailable', () => {
    fc.assert(
      fc.property(locationWithNullSensorsArb, (location: LocationData) => {
        const payload = serializeLocationForTarget(location);
        const jsonString = JSON.stringify(payload);

        // Parse and check all values — none of the optional fields should be -1
        const parsed = JSON.parse(jsonString);
        expect(parsed.speed).not.toBe(-1);
        expect(parsed.heading).not.toBe(-1);
        expect(parsed.altitude).not.toBe(-1);
      }),
      { numRuns: 100 }
    );
  });
});
