/**
 * Unit tests for configuration validation and default application.
 */

import { validateConfig, applyDefaults } from '../../src/validation';
import type { TrackingConfig } from '../../src/types';

describe('validateConfig', () => {
  const validConfig = {
    optimization: {},
    firebase: {
      service: 'RTDB',
      targets: [{ path: '/users/user1/location', method: 'set' }],
    },
  };

  it('valid config passes validation', () => {
    const result = validateConfig(validConfig);
    expect(result.valid).toBe(true);
    expect(result.errors).toHaveLength(0);
  });

  it('missing firebase field returns error', () => {
    const config = { optimization: {} };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase')).toBe(true);
  });

  it('missing firebase.service returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.service')).toBe(true);
  });

  it('invalid firebase.service value returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'InvalidService',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.service')).toBe(true);
  });

  it('missing targets array returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'firebase.targets')
    ).toBe(true);
  });

  it('empty targets array returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'firebase.targets')
    ).toBe(true);
  });

  it('negative intervalMs returns error', () => {
    const config = {
      optimization: { intervalMs: -5000 },
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'optimization.intervalMs')
    ).toBe(true);
  });

  it('negative distanceFilterMeters returns error', () => {
    const config = {
      optimization: { distanceFilterMeters: -10 },
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'optimization.distanceFilterMeters')
    ).toBe(true);
  });

  it('non-boolean stopWhenStill returns error', () => {
    const config = {
      optimization: { stopWhenStill: 'yes' },
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'optimization.stopWhenStill')
    ).toBe(true);
  });

  it('valid optimization mode passes validation', () => {
    const modes = ['interval', 'distance', 'both'];
    for (const mode of modes) {
      const config = {
        optimization: { mode },
        firebase: {
          service: 'RTDB',
          targets: [{ path: '/users/user1/location', method: 'set' }],
        },
      };
      const result = validateConfig(config);
      expect(result.valid).toBe(true);
    }
  });

  it('invalid optimization mode returns error', () => {
    const config = {
      optimization: { mode: 'invalid' },
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'optimization.mode')
    ).toBe(true);
  });

  it('config with single target is valid', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'Firestore',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(true);
  });

  it('config with multiple targets is valid', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [
          { path: '/users/user1/location', method: 'set' },
          { path: '/users/user1/history', method: 'push', batchSize: 10, offlineQueue: true },
        ],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(true);
  });

  it('target with invalid method returns error with target index', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'delete' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'firebase.targets[0].method')
    ).toBe(true);
  });

  it('target with empty path returns error with target index', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'firebase.targets[0].path')
    ).toBe(true);
  });

  it('target with whitespace-only path returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '   ', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'firebase.targets[0].path')
    ).toBe(true);
  });

  it('target with invalid batchSize returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set', batchSize: 0 }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'firebase.targets[0].batchSize')
    ).toBe(true);
  });

  it('target with batchSize > 1000 returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set', batchSize: 1001 }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'firebase.targets[0].batchSize')
    ).toBe(true);
  });

  it('target with non-integer batchSize returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set', batchSize: 3.5 }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'firebase.targets[0].batchSize')
    ).toBe(true);
  });

  it('target with non-boolean offlineQueue returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set', offlineQueue: 'yes' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'firebase.targets[0].offlineQueue')
    ).toBe(true);
  });

  it('duplicate paths return error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [
          { path: '/users/user1/location', method: 'set' },
          { path: '/users/user1/location', method: 'push' },
        ],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.code === 'DUPLICATE_VALUE')
    ).toBe(true);
  });

  it('duplicate path detection is case-sensitive', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [
          { path: '/users/User1/location', method: 'set' },
          { path: '/users/user1/location', method: 'push' },
        ],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(true);
  });

  it('returns all errors at once (does not short-circuit)', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [
          { path: '', method: 'invalid' },
          { path: '   ', method: 'set', batchSize: -1, offlineQueue: 'yes' },
        ],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    // Should have errors for: targets[0].path, targets[0].method, targets[1].path, targets[1].batchSize, targets[1].offlineQueue
    expect(result.errors.length).toBeGreaterThanOrEqual(5);
  });

  it('more than 20 targets returns error', () => {
    const targets = Array.from({ length: 21 }, (_, i) => ({
      path: `/path/${i}`,
      method: 'set',
    }));
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets,
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'firebase.targets')
    ).toBe(true);
  });

  it('path exceeding 768 characters returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: 'a'.repeat(769), method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'firebase.targets[0].path')
    ).toBe(true);
  });

  it('path at exactly 768 characters is valid', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: 'a'.repeat(768), method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(true);
  });

  it('androidNotification with empty title returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
      androidNotification: {
        title: '',
        text: 'Tracking your location',
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'androidNotification.title')
    ).toBe(true);
  });

  it('androidNotification with empty text returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
      androidNotification: {
        title: 'Live Tracking',
        text: '',
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'androidNotification.text')
    ).toBe(true);
  });

  it('androidNotification disabled does not require title/text', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
      androidNotification: {
        enabled: false,
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(true);
  });

  it('androidNotification enabled with empty title still returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
      androidNotification: {
        enabled: true,
        title: '',
        text: 'Tracking your location',
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'androidNotification.title')
    ).toBe(true);
  });

  it('iosNotification disabled does not require title/text', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
      iosNotification: {
        enabled: false,
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(true);
  });

  it('iosNotification enabled with empty title returns error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
      iosNotification: {
        enabled: true,
        title: '',
        text: 'Tracking your location',
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.field === 'iosNotification.title')
    ).toBe(true);
  });

  // ─── Deprecated Field Detection ──────────────────────────────────────────────

  it('currentLocationPath in firebase object returns DEPRECATED_FIELD error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
        currentLocationPath: '/users/user1/current',
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some(
        (e) => e.field === 'firebase.currentLocationPath' && e.code === 'DEPRECATED_FIELD'
      )
    ).toBe(true);
  });

  it('historyPath in firebase object returns DEPRECATED_FIELD error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
        historyPath: '/users/user1/history',
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some(
        (e) => e.field === 'firebase.historyPath' && e.code === 'DEPRECATED_FIELD'
      )
    ).toBe(true);
  });

  it('historyBatchSize in firebase object returns DEPRECATED_FIELD error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
        historyBatchSize: 20,
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some(
        (e) => e.field === 'firebase.historyBatchSize' && e.code === 'DEPRECATED_FIELD'
      )
    ).toBe(true);
  });

  it('rejects config with deprecated fields even when valid targets exist', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
        currentLocationPath: '/users/user1/current',
        historyPath: '/users/user1/history',
        historyBatchSize: 10,
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    const deprecatedErrors = result.errors.filter((e) => e.code === 'DEPRECATED_FIELD');
    expect(deprecatedErrors).toHaveLength(3);
    expect(deprecatedErrors.some((e) => e.field === 'firebase.currentLocationPath')).toBe(true);
    expect(deprecatedErrors.some((e) => e.field === 'firebase.historyPath')).toBe(true);
    expect(deprecatedErrors.some((e) => e.field === 'firebase.historyBatchSize')).toBe(true);
  });

  it('does not flag deprecated fields when they are not present', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(true);
    expect(result.errors.filter((e) => e.code === 'DEPRECATED_FIELD')).toHaveLength(0);
  });
});

describe('applyDefaults', () => {
  it('applies correct defaults', () => {
    const config: TrackingConfig = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
    };

    const result = applyDefaults(config);

    expect(result.optimization.intervalMs).toBe(10000);
    expect(result.optimization.distanceFilterMeters).toBe(10);
    expect(result.optimization.stopWhenStill).toBe(true);
    expect(result.optimization.mode).toBe('both');
  });

  it('preserves explicitly set values', () => {
    const config: TrackingConfig = {
      optimization: {
        intervalMs: 5000,
        distanceFilterMeters: 20,
        stopWhenStill: false,
      },
      firebase: {
        service: 'Firestore',
        targets: [
          { path: '/users/user1/location', method: 'set' },
          { path: '/users/user1/history', method: 'push', batchSize: 10, offlineQueue: true },
        ],
      },
    };

    const result = applyDefaults(config);

    expect(result.optimization.intervalMs).toBe(5000);
    expect(result.optimization.distanceFilterMeters).toBe(20);
    expect(result.optimization.stopWhenStill).toBe(false);
    expect(result.optimization.mode).toBe('both');
    expect(result.firebase.targets).toHaveLength(2);
  });

  it('preserves explicitly set optimization mode', () => {
    const config: TrackingConfig = {
      optimization: {
        mode: 'distance',
      },
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/users/user1/location', method: 'set' }],
      },
    };

    const result = applyDefaults(config);

    expect(result.optimization.mode).toBe('distance');
  });

  it('passes targets through without modification', () => {
    const config: TrackingConfig = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [
          { path: '/path1', method: 'set' },
          { path: '/path2', method: 'push', batchSize: 20, offlineQueue: true },
        ],
      },
    };

    const result = applyDefaults(config);

    expect(result.firebase.targets[0]).toEqual({ path: '/path1', method: 'set' });
    expect(result.firebase.targets[1]).toEqual({ path: '/path2', method: 'push', batchSize: 20, offlineQueue: true });
  });
});
