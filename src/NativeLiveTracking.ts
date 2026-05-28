import { TurboModuleRegistry, NativeModules, Platform } from 'react-native';
import type { TurboModule } from 'react-native';

/**
 * TurboModule spec for react-native-live-tracking.
 * This interface defines the native module contract for the new architecture (codegen).
 */
export interface Spec extends TurboModule {
  configure(config: string): Promise<void>;
  start(): Promise<void>;
  stop(): Promise<void>;
  getStatus(): Promise<string>;
  getQueuedLocations(): Promise<number>;
}

/**
 * Backward-compatible native module access.
 * Supports both TurboModules (new architecture) and Bridge (legacy architecture).
 *
 * - New Architecture (RN >= 0.70 with newArchEnabled): Uses TurboModuleRegistry
 * - Old Architecture (Bridge): Falls back to NativeModules
 */
const LiveTrackingModule: Spec =
  // Try TurboModule first (new architecture)
  TurboModuleRegistry.get<Spec>('LiveTracking') ??
  // Fallback to legacy NativeModules (old architecture / Bridge)
  NativeModules.LiveTracking;

if (!LiveTrackingModule) {
  const message = Platform.select({
    ios:
      "The package 'react-native-live-tracking' doesn't seem to be linked. Make sure:\n" +
      '- You ran `pod install` in the ios directory\n' +
      '- You rebuilt the app after installing the package\n',
    default:
      "The package 'react-native-live-tracking' doesn't seem to be linked. Make sure:\n" +
      '- You rebuilt the app after installing the package\n',
  });
  throw new Error(message);
}

export default LiveTrackingModule;
