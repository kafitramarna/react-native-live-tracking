/**
 * Unit tests for the Distance/Time Matrix filter and distance calculation.
 */

import { shouldAcceptLocation } from '../../src/filters/distanceTimeFilter';
import { calculateDistance } from '../../src/utils/distance';
import type { LocationData } from '../../src/types';

describe('shouldAcceptLocation', () => {
  const config = { intervalMs: 10000, distanceFilterMeters: 10 };

  const baseLocation: LocationData = {
    latitude: -6.2088,
    longitude: 106.8456,
    timestamp: 1700000000000,
    accuracy: 5,
    speed: null,
    altitude: null,
    bearing: null,
  };

  it('accepts location when BOTH time AND distance conditions met', () => {
    // 15 seconds later, ~50m away
    const newLocation: LocationData = {
      latitude: -6.2092,
      longitude: 106.846,
      timestamp: 1700000015000,
      accuracy: 5,
      speed: 1.2,
      altitude: null,
      bearing: null,
    };

    const result = shouldAcceptLocation(baseLocation, newLocation, config);
    expect(result).toBe(true);
  });

  it('rejects location when time condition NOT met (even if distance is met)', () => {
    // Only 5 seconds later (< 10000ms), but far away
    const newLocation: LocationData = {
      latitude: -6.21,
      longitude: 106.85,
      timestamp: 1700000005000,
      accuracy: 5,
      speed: 10,
      altitude: null,
      bearing: null,
    };

    const result = shouldAcceptLocation(baseLocation, newLocation, config);
    expect(result).toBe(false);
  });

  it('rejects location when distance condition NOT met (even if time is met)', () => {
    // 15 seconds later, but very close (< 10m)
    const newLocation: LocationData = {
      latitude: -6.20881,
      longitude: 106.84561,
      timestamp: 1700000015000,
      accuracy: 5,
      speed: 0.1,
      altitude: null,
      bearing: null,
    };

    const result = shouldAcceptLocation(baseLocation, newLocation, config);
    expect(result).toBe(false);
  });

  it('rejects location when NEITHER condition met', () => {
    // Only 2 seconds later and very close
    const newLocation: LocationData = {
      latitude: -6.20881,
      longitude: 106.84561,
      timestamp: 1700000002000,
      accuracy: 5,
      speed: 0,
      altitude: null,
      bearing: null,
    };

    const result = shouldAcceptLocation(baseLocation, newLocation, config);
    expect(result).toBe(false);
  });

  it('interval mode accepts when time condition met even if distance is not met', () => {
    const newLocation: LocationData = {
      latitude: -6.20881,
      longitude: 106.84561,
      timestamp: 1700000015000,
      accuracy: 5,
      speed: 0.1,
      altitude: null,
      bearing: null,
    };

    const result = shouldAcceptLocation(baseLocation, newLocation, {
      ...config,
      mode: 'interval',
    });
    expect(result).toBe(true);
  });

  it('interval mode rejects when time condition is not met', () => {
    const newLocation: LocationData = {
      latitude: -6.21,
      longitude: 106.85,
      timestamp: 1700000005000,
      accuracy: 5,
      speed: 10,
      altitude: null,
      bearing: null,
    };

    const result = shouldAcceptLocation(baseLocation, newLocation, {
      ...config,
      mode: 'interval',
    });
    expect(result).toBe(false);
  });

  it('distance mode accepts when distance condition met even if time is not met', () => {
    const newLocation: LocationData = {
      latitude: -6.21,
      longitude: 106.85,
      timestamp: 1700000005000,
      accuracy: 5,
      speed: 10,
      altitude: null,
      bearing: null,
    };

    const result = shouldAcceptLocation(baseLocation, newLocation, {
      ...config,
      mode: 'distance',
    });
    expect(result).toBe(true);
  });

  it('distance mode rejects when distance condition is not met', () => {
    const newLocation: LocationData = {
      latitude: -6.20881,
      longitude: 106.84561,
      timestamp: 1700000015000,
      accuracy: 5,
      speed: 0.1,
      altitude: null,
      bearing: null,
    };

    const result = shouldAcceptLocation(baseLocation, newLocation, {
      ...config,
      mode: 'distance',
    });
    expect(result).toBe(false);
  });
});

describe('calculateDistance', () => {
  it('same point returns 0 meters', () => {
    const distance = calculateDistance(-6.2088, 106.8456, -6.2088, 106.8456);
    expect(distance).toBe(0);
  });

  it('known distance between two cities (approximate)', () => {
    // Jakarta to Bandung: approximately 120 km
    const distance = calculateDistance(-6.2088, 106.8456, -6.9175, 107.6191);
    // Allow 10% tolerance for Haversine approximation
    expect(distance).toBeGreaterThan(100000);
    expect(distance).toBeLessThan(140000);
  });

  it('very small movements (< 1 meter)', () => {
    // Move ~0.5 meters (approximately 0.000005 degrees latitude)
    const distance = calculateDistance(
      -6.2088,
      106.8456,
      -6.2088045,
      106.8456
    );
    expect(distance).toBeLessThan(1);
    expect(distance).toBeGreaterThanOrEqual(0);
  });
});
