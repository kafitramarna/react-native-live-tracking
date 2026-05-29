/**
 * Unit tests for sync target validation.
 *
 * Tests specific examples for sync target field validation, deprecated field detection,
 * and Firestore + push method acceptance.
 *
 * Validates: Requirements 1.2, 1.3, 1.4, 1.5, 2.1, 2.2, 2.3, 6.1, 6.2, 6.3, 6.4, 6.5, 6.7
 */

import { validateConfig } from '../../src/validation';

// ─── Helper: Build a valid base config ────────────────────────────────────────

function baseConfig(overrides: Record<string, unknown> = {}) {
  return {
    optimization: {},
    firebase: {
      service: 'RTDB',
      targets: [{ path: '/valid/path', method: 'set' }],
      ...overrides,
    },
  };
}

function configWithTarget(target: Record<string, unknown>) {
  return {
    optimization: {},
    firebase: {
      service: 'RTDB',
      targets: [target],
    },
  };
}

// ─── Sync Target Path Validation (Requirement 1.2, 6.1) ──────────────────────

describe('Sync Target Path Validation', () => {
  it('rejects empty string path with structured error containing target index', () => {
    const result = validateConfig(configWithTarget({ path: '', method: 'set' }));
    expect(result.valid).toBe(false);
    const error = result.errors.find((e) => e.field === 'firebase.targets[0].path');
    expect(error).toBeDefined();
    expect(error!.message).toContain('empty');
  });

  it('rejects whitespace-only path with structured error containing target index', () => {
    const result = validateConfig(configWithTarget({ path: '   \t\n  ', method: 'set' }));
    expect(result.valid).toBe(false);
    const error = result.errors.find((e) => e.field === 'firebase.targets[0].path');
    expect(error).toBeDefined();
    expect(error!.message).toContain('whitespace');
  });

  it('rejects path with only spaces', () => {
    const result = validateConfig(configWithTarget({ path: '     ', method: 'set' }));
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.targets[0].path')).toBe(true);
  });

  it('accepts a valid non-empty path', () => {
    const result = validateConfig(configWithTarget({ path: '/users/abc/location', method: 'set' }));
    expect(result.valid).toBe(true);
    expect(result.errors).toHaveLength(0);
  });

  it('includes target index in error field for second target', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [
          { path: '/valid/path', method: 'set' },
          { path: '', method: 'push' },
        ],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.targets[1].path')).toBe(true);
  });
});

// ─── Sync Target Method Validation (Requirement 1.3, 6.2) ────────────────────

describe('Sync Target Method Validation', () => {
  it('rejects invalid method value with structured error', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'delete' }));
    expect(result.valid).toBe(false);
    const error = result.errors.find((e) => e.field === 'firebase.targets[0].method');
    expect(error).toBeDefined();
    expect(error!.message).toContain('delete');
  });

  it('rejects numeric method value', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 123 }));
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.targets[0].method')).toBe(true);
  });

  it('rejects null method', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: null }));
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.targets[0].method')).toBe(true);
  });

  it('rejects undefined method', () => {
    const result = validateConfig(configWithTarget({ path: '/path' }));
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.targets[0].method')).toBe(true);
  });

  it('accepts method "set"', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set' }));
    expect(result.valid).toBe(true);
  });

  it('accepts method "push"', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'push' }));
    expect(result.valid).toBe(true);
  });

  it('accepts method "update"', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'update' }));
    expect(result.valid).toBe(true);
  });
});

// ─── Sync Target batchSize Validation (Requirement 1.4, 6.3) ─────────────────

describe('Sync Target batchSize Validation', () => {
  it('rejects batchSize less than 1', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', batchSize: 0 }));
    expect(result.valid).toBe(false);
    const error = result.errors.find((e) => e.field === 'firebase.targets[0].batchSize');
    expect(error).toBeDefined();
  });

  it('rejects negative batchSize', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', batchSize: -5 }));
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.targets[0].batchSize')).toBe(true);
  });

  it('rejects batchSize greater than 1000', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', batchSize: 1001 }));
    expect(result.valid).toBe(false);
    const error = result.errors.find((e) => e.field === 'firebase.targets[0].batchSize');
    expect(error).toBeDefined();
  });

  it('rejects non-integer batchSize (3.5)', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', batchSize: 3.5 }));
    expect(result.valid).toBe(false);
    const error = result.errors.find((e) => e.field === 'firebase.targets[0].batchSize');
    expect(error).toBeDefined();
  });

  it('rejects non-integer batchSize (99.9)', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', batchSize: 99.9 }));
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.targets[0].batchSize')).toBe(true);
  });

  it('rejects NaN batchSize', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', batchSize: NaN }));
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.targets[0].batchSize')).toBe(true);
  });

  it('rejects Infinity batchSize', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', batchSize: Infinity }));
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.targets[0].batchSize')).toBe(true);
  });

  it('rejects string batchSize', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', batchSize: '10' }));
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.targets[0].batchSize')).toBe(true);
  });

  it('accepts batchSize of 1 (minimum)', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', batchSize: 1 }));
    expect(result.valid).toBe(true);
  });

  it('accepts batchSize of 1000 (maximum)', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', batchSize: 1000 }));
    expect(result.valid).toBe(true);
  });

  it('accepts batchSize of 500 (mid-range)', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', batchSize: 500 }));
    expect(result.valid).toBe(true);
  });

  it('accepts undefined batchSize (optional field)', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set' }));
    expect(result.valid).toBe(true);
  });
});

// ─── Sync Target offlineQueue Validation (Requirement 1.5, 6.7) ──────────────

describe('Sync Target offlineQueue Validation', () => {
  it('rejects non-boolean offlineQueue (string "yes")', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', offlineQueue: 'yes' }));
    expect(result.valid).toBe(false);
    const error = result.errors.find((e) => e.field === 'firebase.targets[0].offlineQueue');
    expect(error).toBeDefined();
    expect(error!.code).toBe('INVALID_TYPE');
  });

  it('rejects non-boolean offlineQueue (number 1)', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', offlineQueue: 1 }));
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.targets[0].offlineQueue')).toBe(true);
  });

  it('rejects non-boolean offlineQueue (null)', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', offlineQueue: null }));
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.targets[0].offlineQueue')).toBe(true);
  });

  it('accepts offlineQueue: true', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', offlineQueue: true }));
    expect(result.valid).toBe(true);
  });

  it('accepts offlineQueue: false', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set', offlineQueue: false }));
    expect(result.valid).toBe(true);
  });

  it('accepts undefined offlineQueue (optional field)', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set' }));
    expect(result.valid).toBe(true);
  });
});

// ─── Deprecated Field Detection (Requirements 2.1, 2.2, 2.3) ─────────────────

describe('Deprecated Field Detection', () => {
  it('detects currentLocationPath individually with DEPRECATED_FIELD code', () => {
    const config = baseConfig({ currentLocationPath: '/users/user1/current' });
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    const error = result.errors.find(
      (e) => e.field === 'firebase.currentLocationPath' && e.code === 'DEPRECATED_FIELD'
    );
    expect(error).toBeDefined();
    expect(error!.message).toContain('deprecated');
  });

  it('detects historyPath individually with DEPRECATED_FIELD code', () => {
    const config = baseConfig({ historyPath: '/users/user1/history' });
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    const error = result.errors.find(
      (e) => e.field === 'firebase.historyPath' && e.code === 'DEPRECATED_FIELD'
    );
    expect(error).toBeDefined();
    expect(error!.message).toContain('deprecated');
  });

  it('detects historyBatchSize individually with DEPRECATED_FIELD code', () => {
    const config = baseConfig({ historyBatchSize: 20 });
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    const error = result.errors.find(
      (e) => e.field === 'firebase.historyBatchSize' && e.code === 'DEPRECATED_FIELD'
    );
    expect(error).toBeDefined();
    expect(error!.message).toContain('deprecated');
  });

  it('detects all three deprecated fields combined', () => {
    const config = baseConfig({
      currentLocationPath: '/users/user1/current',
      historyPath: '/users/user1/history',
      historyBatchSize: 10,
    });
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    const deprecatedErrors = result.errors.filter((e) => e.code === 'DEPRECATED_FIELD');
    expect(deprecatedErrors).toHaveLength(3);
    expect(deprecatedErrors.some((e) => e.field === 'firebase.currentLocationPath')).toBe(true);
    expect(deprecatedErrors.some((e) => e.field === 'firebase.historyPath')).toBe(true);
    expect(deprecatedErrors.some((e) => e.field === 'firebase.historyBatchSize')).toBe(true);
  });

  it('rejects config with deprecated fields even when valid targets exist', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/valid/path', method: 'set' }],
        currentLocationPath: '/old/path',
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'DEPRECATED_FIELD')).toBe(true);
  });

  it('does not flag deprecated fields when they are absent', () => {
    const config = baseConfig();
    const result = validateConfig(config);
    expect(result.valid).toBe(true);
    expect(result.errors.filter((e) => e.code === 'DEPRECATED_FIELD')).toHaveLength(0);
  });
});

// ─── push + Firestore Acceptance (Requirement 6.5) ────────────────────────────

describe('push + Firestore Acceptance', () => {
  it('accepts push method with Firestore service without error', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'Firestore',
        targets: [{ path: '/collections/trips/history', method: 'push' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(true);
    expect(result.errors).toHaveLength(0);
  });

  it('accepts push method with Firestore and batchSize', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'Firestore',
        targets: [{ path: '/collections/trips/history', method: 'push', batchSize: 50, offlineQueue: true }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(true);
    expect(result.errors).toHaveLength(0);
  });

  it('accepts push method with RTDB service', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'RTDB',
        targets: [{ path: '/trips/history', method: 'push' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(true);
    expect(result.errors).toHaveLength(0);
  });
});

// ─── firebase.service Validation (Requirement 6.4) ────────────────────────────

describe('firebase.service Validation', () => {
  it('rejects invalid service value', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'MongoDB',
        targets: [{ path: '/path', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    const error = result.errors.find((e) => e.field === 'firebase.service');
    expect(error).toBeDefined();
    expect(error!.message).toContain('MongoDB');
  });

  it('rejects null service', () => {
    const config = {
      optimization: {},
      firebase: {
        service: null,
        targets: [{ path: '/path', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.service')).toBe(true);
  });

  it('rejects undefined service', () => {
    const config = {
      optimization: {},
      firebase: {
        targets: [{ path: '/path', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.field === 'firebase.service')).toBe(true);
  });

  it('accepts RTDB service', () => {
    const result = validateConfig(configWithTarget({ path: '/path', method: 'set' }));
    expect(result.valid).toBe(true);
  });

  it('accepts Firestore service', () => {
    const config = {
      optimization: {},
      firebase: {
        service: 'Firestore',
        targets: [{ path: '/path', method: 'set' }],
      },
    };
    const result = validateConfig(config);
    expect(result.valid).toBe(true);
  });
});
