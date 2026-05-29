/**
 * Unit tests for location serialization.
 */

import { serializeLocationForTarget } from '../../src/serialization/locationSerializer';
import type { LocationData } from '../../src/types';

describe('serializeLocationForTarget', () => {
  it('includes latitude, longitude, timestamp, accuracy for all locations', () => {
    const location: LocationData = {
      latitude: -6.2088,
      longitude: 106.8456,
      timestamp: 1700000000000,
      accuracy: 5.2,
      speed: 1.5,
      altitude: 45.0,
      bearing: 180.0,
    };

    const payload = serializeLocationForTarget(location);

    expect(payload.latitude).toBe(-6.2088);
    expect(payload.longitude).toBe(106.8456);
    expect(payload.timestamp).toBe(1700000000000);
    expect(payload.accuracy).toBe(5.2);
  });

  it('includes speed, heading, altitude when available', () => {
    const location: LocationData = {
      latitude: 40.7128,
      longitude: -74.006,
      timestamp: 1700000050000,
      accuracy: 3.0,
      speed: 5.5,
      altitude: 120.3,
      bearing: 270.0,
    };

    const payload = serializeLocationForTarget(location);

    expect(payload.speed).toBe(5.5);
    expect(payload.heading).toBe(270.0);
    expect(payload.altitude).toBe(120.3);
  });

  it('sets speed, heading, altitude to null when unavailable', () => {
    const location: LocationData = {
      latitude: -6.2088,
      longitude: 106.8456,
      timestamp: 1700000000000,
      accuracy: 5.2,
      speed: null,
      altitude: null,
      bearing: null,
    };

    const payload = serializeLocationForTarget(location);

    expect(payload.speed).toBeNull();
    expect(payload.heading).toBeNull();
    expect(payload.altitude).toBeNull();
  });

  it('never writes placeholder values (0, -1) for unavailable fields', () => {
    const location: LocationData = {
      latitude: 0,
      longitude: 0,
      timestamp: 1700000000000,
      accuracy: 10.0,
      speed: null,
      altitude: null,
      bearing: null,
    };

    const payload = serializeLocationForTarget(location);

    // Optional fields should be null, not 0 or -1
    expect(payload.speed).not.toBe(0);
    expect(payload.speed).not.toBe(-1);
    expect(payload.heading).not.toBe(0);
    expect(payload.heading).not.toBe(-1);
    expect(payload.altitude).not.toBe(0);
    expect(payload.altitude).not.toBe(-1);

    expect(payload.speed).toBeNull();
    expect(payload.heading).toBeNull();
    expect(payload.altitude).toBeNull();
  });

  it('maps bearing field from LocationData to heading in payload', () => {
    const location: LocationData = {
      latitude: 51.5074,
      longitude: -0.1278,
      timestamp: 1700000100000,
      accuracy: 8.0,
      speed: 2.0,
      altitude: 30.0,
      bearing: 90.0,
    };

    const payload = serializeLocationForTarget(location);

    expect(payload.heading).toBe(90.0);
    expect(payload).not.toHaveProperty('bearing');
  });

  it('produces a payload with exactly the expected keys', () => {
    const location: LocationData = {
      latitude: 35.6762,
      longitude: 139.6503,
      timestamp: 1700000200000,
      accuracy: 4.5,
      speed: 3.2,
      altitude: 50.0,
      bearing: 45.0,
    };

    const payload = serializeLocationForTarget(location);
    const keys = Object.keys(payload).sort();

    expect(keys).toEqual(
      ['accuracy', 'altitude', 'heading', 'latitude', 'longitude', 'speed', 'timestamp'].sort()
    );
  });
});
