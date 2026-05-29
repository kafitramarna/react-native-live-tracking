import { NativeModules, Platform } from 'react-native';

/**
 * Native module interface for react-native-live-tracking.
 *
 * Uses NativeModules (Bridge) which is automatically wrapped by
 * React Native's interop layer when New Architecture is enabled.
 * This provides full compatibility with both old and new architecture
 * without requiring codegen setup.
 */
interface LiveTrackingNativeModule {
  configure(config: string): Promise<void>;
  start(): Promise<void>;
  stop(): Promise<void>;
  getStatus(): Promise<string>;
  getQueuedLocations(): Promise<number>;
  getQueuedLocationsByTarget(): Promise<string>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

const LiveTrackingModule: LiveTrackingNativeModule = NativeModules.LiveTracking;

if (!LiveTrackingModule) {
  const message = Platform.select({
    ios:
      "The package '@kafitra/react-native-live-tracking' doesn't seem to be linked. Make sure:\n" +
      '- You ran `pod install` in the ios directory\n' +
      '- You rebuilt the app after installing the package\n',
    default:
      "The package '@kafitra/react-native-live-tracking' doesn't seem to be linked. Make sure:\n" +
      '- You rebuilt the app after installing the package\n',
  });
  throw new Error(message!);
}

export default LiveTrackingModule;
