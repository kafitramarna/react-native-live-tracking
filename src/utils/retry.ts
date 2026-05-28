/**
 * Exponential backoff retry utilities for Firebase write operations.
 *
 * Used by the Firebase Sync Engine to determine delay between retry attempts
 * when writes fail due to network issues or transient errors.
 *
 * @module utils/retry
 */

/** Default base delay in milliseconds for exponential backoff */
export const DEFAULT_BASE_DELAY = 1000;

/** Default maximum jitter in milliseconds (±) to avoid thundering herd */
export const DEFAULT_MAX_JITTER = 200;

/** Maximum retry attempts for current location writes */
export const MAX_RETRIES_CURRENT_LOCATION = 3;

/** Maximum retry attempts for history batch writes */
export const MAX_RETRIES_HISTORY_BATCH = 5;

/**
 * Calculates the delay before the next retry attempt using exponential backoff
 * with random jitter.
 *
 * Formula: delay = baseDelay × 2^(attempt - 1) + random(-maxJitter, +maxJitter)
 *
 * The result is clamped to a minimum of 0 to prevent negative delays.
 *
 * @param attempt - The retry attempt number (1-indexed, first retry = 1)
 * @param baseDelay - The base delay in milliseconds (default: 1000ms)
 * @param maxJitter - The maximum jitter offset in milliseconds (default: 200ms).
 *   A random value between -maxJitter and +maxJitter is added to the delay.
 * @returns The calculated delay in milliseconds (never negative)
 *
 * @example
 * ```typescript
 * // First retry: ~1000ms ± 200ms
 * const delay1 = calculateBackoffDelay(1);
 *
 * // Second retry: ~2000ms ± 200ms
 * const delay2 = calculateBackoffDelay(2);
 *
 * // Third retry: ~4000ms ± 200ms
 * const delay3 = calculateBackoffDelay(3);
 * ```
 */
export function calculateBackoffDelay(
  attempt: number,
  baseDelay: number = DEFAULT_BASE_DELAY,
  maxJitter: number = DEFAULT_MAX_JITTER
): number {
  const exponentialDelay = baseDelay * Math.pow(2, attempt - 1);
  const jitter = (Math.random() * 2 - 1) * maxJitter;
  return Math.max(0, exponentialDelay + jitter);
}

/**
 * Determines whether a retry should be attempted based on the current attempt
 * number and the maximum allowed retries.
 *
 * @param attempt - The current attempt number (1-indexed)
 * @param maxRetries - The maximum number of retries allowed
 * @returns `true` if the attempt is within the allowed retry limit, `false` otherwise
 *
 * @example
 * ```typescript
 * shouldRetry(1, 3); // true - first retry is allowed
 * shouldRetry(3, 3); // true - third retry is allowed
 * shouldRetry(4, 3); // false - exceeds max retries
 * ```
 */
export function shouldRetry(attempt: number, maxRetries: number): boolean {
  return attempt <= maxRetries;
}
