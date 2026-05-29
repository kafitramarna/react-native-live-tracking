/**
 * Unit tests for the LiveTracking module with mocked native module.
 */

import { NativeModules } from 'react-native';

// Mock NativeLiveTracking to use the mocked NativeModules.LiveTracking
jest.mock('../../src/NativeLiveTracking', () => {
  const { NativeModules: NM } = require('react-native');
  return {
    __esModule: true,
    default: NM.LiveTracking,
  };
});

import LiveTracking, { _resetConfiguredStateForTesting } from '../../src/LiveTracking';

describe('LiveTracking module', () => {
  const nativeModule = NativeModules.LiveTracking;

  const validConfig = {
    optimization: {},
    firebase: {
      service: 'RTDB' as const,
      targets: [{ path: '/users/user1/location', method: 'set' as const }] as [{ path: string; method: 'set' }],
    },
  };

  beforeEach(() => {
    jest.clearAllMocks();
    _resetConfiguredStateForTesting();
  });

  describe('configure()', () => {
    it('with valid config calls native configure with JSON string', async () => {
      await LiveTracking.configure(validConfig);

      expect(nativeModule.configure).toHaveBeenCalledTimes(1);
      const callArg = nativeModule.configure.mock.calls[0][0];
      const parsed = JSON.parse(callArg);
      expect(parsed.firebase.service).toBe('RTDB');
      expect(parsed.firebase.targets[0].path).toBe('/users/user1/location');
      expect(parsed.firebase.targets[0].method).toBe('set');
      // Defaults should be applied
      expect(parsed.optimization.intervalMs).toBe(10000);
      expect(parsed.optimization.distanceFilterMeters).toBe(10);
      expect(parsed.optimization.stopWhenStill).toBe(true);
    });

    it('with invalid config throws error without calling native', async () => {
      const invalidConfig = {
        optimization: {},
        firebase: {
          // Missing service and targets
        },
      };

      await expect(
        LiveTracking.configure(invalidConfig as any)
      ).rejects.toThrow('Invalid configuration');

      expect(nativeModule.configure).not.toHaveBeenCalled();
    });

    it('omits undefined fields from SyncTarget in serialized JSON', async () => {
      const config = {
        optimization: {},
        firebase: {
          service: 'RTDB' as const,
          targets: [
            { path: '/users/u1/loc', method: 'set' as const, batchSize: undefined, offlineQueue: undefined },
          ] as [{ path: string; method: 'set'; batchSize: undefined; offlineQueue: undefined }],
        },
      };

      await LiveTracking.configure(config);

      const callArg = nativeModule.configure.mock.calls[0][0];
      const parsed = JSON.parse(callArg);
      expect(parsed.firebase.targets[0]).not.toHaveProperty('batchSize');
      expect(parsed.firebase.targets[0]).not.toHaveProperty('offlineQueue');
    });

    it('includes defined optional fields in serialized JSON', async () => {
      const config = {
        optimization: {},
        firebase: {
          service: 'Firestore' as const,
          targets: [
            { path: '/trips/t1/history', method: 'push' as const, batchSize: 20, offlineQueue: true },
          ] as [{ path: string; method: 'push'; batchSize: number; offlineQueue: boolean }],
        },
      };

      await LiveTracking.configure(config);

      const callArg = nativeModule.configure.mock.calls[0][0];
      const parsed = JSON.parse(callArg);
      expect(parsed.firebase.targets[0].batchSize).toBe(20);
      expect(parsed.firebase.targets[0].offlineQueue).toBe(true);
    });

    it('rejects if serialized JSON exceeds 1 MB', async () => {
      // Use androidNotification with very long strings to exceed 1 MB
      // (androidNotification title/text have no max length in validation)
      const longText = 'x'.repeat(1024 * 1024); // 1 MB string in title alone

      const config = {
        optimization: {},
        firebase: {
          service: 'RTDB' as const,
          targets: [{ path: '/loc', method: 'set' as const }] as [{ path: string; method: 'set' }],
        },
        androidNotification: {
          title: longText,
          text: 'some text',
        },
      };

      await expect(
        LiveTracking.configure(config)
      ).rejects.toThrow('Configuration payload is too large');

      expect(nativeModule.configure).not.toHaveBeenCalled();
    });
  });

  describe('start()', () => {
    it('calls native start()', async () => {
      await LiveTracking.start();
      expect(nativeModule.start).toHaveBeenCalledTimes(1);
    });
  });

  describe('stop()', () => {
    it('calls native stop()', async () => {
      await LiveTracking.stop();
      expect(nativeModule.stop).toHaveBeenCalledTimes(1);
    });
  });

  describe('getStatus()', () => {
    it('calls native and parses JSON response', async () => {
      const mockStatus = {
        state: 'tracking',
        isOnline: true,
        queuedLocations: 5,
        lastLocation: null,
        batteryOptimization: 'full_accuracy',
      };
      nativeModule.getStatus.mockResolvedValue(JSON.stringify(mockStatus));

      const status = await LiveTracking.getStatus();

      expect(nativeModule.getStatus).toHaveBeenCalledTimes(1);
      expect(status.state).toBe('tracking');
      expect(status.isOnline).toBe(true);
      expect(status.queuedLocations).toBe(5);
      expect(status.lastLocation).toBeNull();
      expect(status.batteryOptimization).toBe('full_accuracy');
    });
  });

  describe('getQueuedLocations()', () => {
    it('returns number from native after configure', async () => {
      // Must configure first
      await LiveTracking.configure(validConfig);
      nativeModule.getQueuedLocations.mockResolvedValue(12);

      const count = await LiveTracking.getQueuedLocations();

      expect(nativeModule.getQueuedLocations).toHaveBeenCalledTimes(1);
      expect(count).toBe(12);
    });

    it('rejects with NOT_CONFIGURED if called before configure()', async () => {
      try {
        await LiveTracking.getQueuedLocations();
        fail('Expected error to be thrown');
      } catch (error: any) {
        expect(error.code).toBe('NOT_CONFIGURED');
        expect(error.message).toContain('not configured');
      }
      expect(nativeModule.getQueuedLocations).not.toHaveBeenCalled();
    });
  });

  describe('getQueuedLocationsByTarget()', () => {
    it('returns parsed record from native JSON after configure', async () => {
      // Must configure first
      await LiveTracking.configure(validConfig);
      const mockResult = { '/users/user1/location': 5, '/trips/t1/history': 10 };
      nativeModule.getQueuedLocationsByTarget.mockResolvedValue(JSON.stringify(mockResult));

      const result = await LiveTracking.getQueuedLocationsByTarget();

      expect(nativeModule.getQueuedLocationsByTarget).toHaveBeenCalledTimes(1);
      expect(result).toEqual(mockResult);
    });

    it('rejects with NOT_CONFIGURED if called before configure()', async () => {
      try {
        await LiveTracking.getQueuedLocationsByTarget();
        fail('Expected error to be thrown');
      } catch (error: any) {
        expect(error.code).toBe('NOT_CONFIGURED');
        expect(error.message).toContain('not configured');
      }
      expect(nativeModule.getQueuedLocationsByTarget).not.toHaveBeenCalled();
    });
  });
});
