/**
 * Unit tests for exponential backoff retry utilities.
 */

import { calculateBackoffDelay, shouldRetry } from '../../src/utils/retry';

describe('calculateBackoffDelay', () => {
  it('attempt 1: delay ≈ 1000ms (±200ms jitter)', () => {
    const delay = calculateBackoffDelay(1);
    expect(delay).toBeGreaterThanOrEqual(800);
    expect(delay).toBeLessThanOrEqual(1200);
  });

  it('attempt 2: delay ≈ 2000ms (±200ms jitter)', () => {
    const delay = calculateBackoffDelay(2);
    expect(delay).toBeGreaterThanOrEqual(1800);
    expect(delay).toBeLessThanOrEqual(2200);
  });

  it('attempt 3: delay ≈ 4000ms (±200ms jitter)', () => {
    const delay = calculateBackoffDelay(3);
    expect(delay).toBeGreaterThanOrEqual(3800);
    expect(delay).toBeLessThanOrEqual(4200);
  });

  it('delay is never negative', () => {
    // Run multiple times to account for randomness
    for (let i = 0; i < 100; i++) {
      const delay = calculateBackoffDelay(1, 100, 200);
      expect(delay).toBeGreaterThanOrEqual(0);
    }
  });
});

describe('shouldRetry', () => {
  it('shouldRetry(1, 3) returns true', () => {
    expect(shouldRetry(1, 3)).toBe(true);
  });

  it('shouldRetry(3, 3) returns true', () => {
    expect(shouldRetry(3, 3)).toBe(true);
  });

  it('shouldRetry(4, 3) returns false', () => {
    expect(shouldRetry(4, 3)).toBe(false);
  });

  it('shouldRetry(6, 5) returns false', () => {
    expect(shouldRetry(6, 5)).toBe(false);
  });
});
