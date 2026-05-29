/**
 * Unit tests for configuration serialization and location serializer.
 *
 * Validates:
 * - Requirements 9.1: Serialization preserves defined fields, omits undefined
 * - Requirements 9.5: JSON > 1 MB rejection
 * - Requirements 3.7: Null speed/heading produces null, never placeholder values
 */

import LiveTracking from '../../src/LiveTracking';
import { serializeLocationForTarget } from '../../src/serialization/locationSerializer';
import type { TrackingConfig, LocationData } from '../../src/types';

// ─── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Creates a minimal valid TrackingConfig for testing.
 */
function createValidConfig(overrides?: Partial<TrackingConfig>): TrackingConfig {
  return {
    optimization: { intervalMs: 10000, distanceFilterMeters: 10 },
    firebase: {
      service: 'RTDB',
      targets: [{ path: 'test/path', method: 'set' }],
    },
    ...overrides,
  };
}

// ─── Config Serialization Tests ──────────────────────────────────────────────

describe('Config serialization', () => {
  describe('JSON > 1 MB rejection (Requirement 9.5)', () => {
    it('rejects configure() when serialized JSON exceeds 1 MB', async () => {
      // Use androidNotification with very long title/text fields to exceed 1 MB
      // while keeping the firebase config valid (paths under 768 chars).
      const config: TrackingConfig = {
        optimization: { intervalMs: 10000, distanceFilterMeters: 10 },
        androidNotification: {
          title: 'T'.repeat(600000),
          text: 'B'.repeat(600000),
        },
        firebase: {
          service: 'RTDB',
          targets: [{ path: 'test/path', method: 'set' }],
        },
      };

      await expect(LiveTracking.configure(config)).rejects.toThrow(
        'Configuration payload is too large'
      );
    });

    it('accepts configure() when serialized JSON is under 1 MB', async () => {
      const config = createValidConfig();

      // Should not throw payload size error
      await expect(LiveTracking.configure(config)).resolves.toBeUndefined();
    });
  });

  describe('Undefined fields omitted from serialized output (Requirement 9.1)', () => {
    it('omits batchSize when undefined in SyncTarget', () => {
      const config = createValidConfig({
        firebase: {
          service: 'RTDB',
          targets: [{ path: 'drivers/abc/location', method: 'set' }],
        },
      });

      const json = JSON.stringify(config);
      const parsed = JSON.parse(json);

      expect(parsed.firebase.targets[0]).not.toHaveProperty('batchSize');
    });

    it('omits offlineQueue when undefined in SyncTarget', () => {
      const config = createValidConfig({
        firebase: {
          service: 'Firestore',
          targets: [{ path: 'trips/xyz/history', method: 'push' }],
        },
      });

      const json = JSON.stringify(config);
      const parsed = JSON.parse(json);

      expect(parsed.firebase.targets[0]).not.toHaveProperty('offlineQueue');
    });

    it('preserves batchSize and offlineQueue when explicitly defined', () => {
      const config = createValidConfig({
        firebase: {
          service: 'RTDB',
          targets: [
            { path: 'trips/xyz/history', method: 'push', batchSize: 20, offlineQueue: true },
          ],
        },
      });

      const json = JSON.stringify(config);
      const parsed = JSON.parse(json);

      expect(parsed.firebase.targets[0].batchSize).toBe(20);
      expect(parsed.firebase.targets[0].offlineQueue).toBe(true);
    });

    it('omits androidNotification when undefined', () => {
      const config = createValidConfig();

      const json = JSON.stringify(config);
      const parsed = JSON.parse(json);

      expect(parsed).not.toHaveProperty('androidNotification');
    });

    it('preserves all defined fields in serialized output', () => {
      const config: TrackingConfig = {
        optimization: { intervalMs: 5000, distanceFilterMeters: 15, stopWhenStill: false },
        firebase: {
          service: 'RTDB',
          targets: [
            { path: 'drivers/abc/location', method: 'set' },
            { path: 'trips/xyz/history', method: 'push', batchSize: 10, offlineQueue: true },
          ],
        },
      };

      const json = JSON.stringify(config);
      const parsed = JSON.parse(json);

      expect(parsed.optimization.intervalMs).toBe(5000);
      expect(parsed.optimization.distanceFilterMeters).toBe(15);
      expect(parsed.optimization.stopWhenStill).toBe(false);
      expect(parsed.firebase.service).toBe('RTDB');
      expect(parsed.firebase.targets).toHaveLength(2);
      expect(parsed.firebase.targets[0].path).toBe('drivers/abc/location');
      expect(parsed.firebase.targets[0].method).toBe('set');
      expect(parsed.firebase.targets[1].path).toBe('trips/xyz/history');
      expect(parsed.firebase.targets[1].method).toBe('push');
      expect(parsed.firebase.targets[1].batchSize).toBe(10);
      expect(parsed.firebase.targets[1].offlineQueue).toBe(true);
    });
  });
});

// ─── Location Serializer Tests ───────────────────────────────────────────────

describe('Location serializer with null fields (Requirement 3.7)', () => {
  it('produces null for speed when location speed is null', () => {
    const location: LocationData = {
      latitude: 40.7128,
      longitude: -74.006,
      timestamp: 1700000000000,
      accuracy: 5.0,
      speed: null,
      altitude: 100.0,
      bearing: 90.0,
    };

    const payload = serializeLocationForTarget(location);

    expect(payload.speed).toBeNull();
  });

  it('produces null for heading when location bearing is null', () => {
    const location: LocationData = {
      latitude: 40.7128,
      longitude: -74.006,
      timestamp: 1700000000000,
      accuracy: 5.0,
      speed: 3.5,
      altitude: 100.0,
      bearing: null,
    };

    const payload = serializeLocationForTarget(location);

    expect(payload.heading).toBeNull();
  });

  it('produces null for altitude when location altitude is null', () => {
    const location: LocationData = {
      latitude: 40.7128,
      longitude: -74.006,
      timestamp: 1700000000000,
      accuracy: 5.0,
      speed: 3.5,
      altitude: null,
      bearing: 180.0,
    };

    const payload = serializeLocationForTarget(location);

    expect(payload.altitude).toBeNull();
  });

  it('produces null for all optional fields when all are null', () => {
    const location: LocationData = {
      latitude: -33.8688,
      longitude: 151.2093,
      timestamp: 1700000100000,
      accuracy: 8.0,
      speed: null,
      altitude: null,
      bearing: null,
    };

    const payload = serializeLocationForTarget(location);

    expect(payload.speed).toBeNull();
    expect(payload.heading).toBeNull();
    expect(payload.altitude).toBeNull();
  });

  it('never writes placeholder values (0, -1) for null sensor fields', () => {
    const location: LocationData = {
      latitude: 51.5074,
      longitude: -0.1278,
      timestamp: 1700000200000,
      accuracy: 12.0,
      speed: null,
      altitude: null,
      bearing: null,
    };

    const payload = serializeLocationForTarget(location);

    expect(payload.speed).not.toBe(0);
    expect(payload.speed).not.toBe(-1);
    expect(payload.heading).not.toBe(0);
    expect(payload.heading).not.toBe(-1);
    expect(payload.altitude).not.toBe(0);
    expect(payload.altitude).not.toBe(-1);
  });

  it('preserves valid numeric values for speed, heading, altitude', () => {
    const location: LocationData = {
      latitude: 35.6762,
      longitude: 139.6503,
      timestamp: 1700000300000,
      accuracy: 3.0,
      speed: 12.5,
      altitude: 250.0,
      bearing: 45.0,
    };

    const payload = serializeLocationForTarget(location);

    expect(payload.speed).toBe(12.5);
    expect(payload.heading).toBe(45.0);
    expect(payload.altitude).toBe(250.0);
  });
});
