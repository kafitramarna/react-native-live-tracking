/**
 * Jest setup file for react-native-live-tracking tests.
 *
 * Mocks react-native modules that are not available in the Node test environment.
 */

jest.mock('react-native', () => {
  const addListenerMock = jest.fn(() => ({ remove: jest.fn() }));
  const removeAllListenersMock = jest.fn();

  return {
    NativeModules: {
      LiveTracking: {
        configure: jest.fn().mockResolvedValue(undefined),
        start: jest.fn().mockResolvedValue(undefined),
        stop: jest.fn().mockResolvedValue(undefined),
        getStatus: jest.fn().mockResolvedValue('{}'),
        getQueuedLocations: jest.fn().mockResolvedValue(0),
        getQueuedLocationsByTarget: jest.fn().mockResolvedValue('{}'),
      },
    },
    NativeEventEmitter: jest.fn().mockImplementation(() => ({
      addListener: addListenerMock,
      removeAllListeners: removeAllListenersMock,
    })),
    TurboModuleRegistry: {
      get: jest.fn().mockReturnValue(null),
    },
    Platform: {
      OS: 'ios',
      select: jest.fn((obj: Record<string, unknown>) => obj.ios ?? obj.default),
    },
  };
});
