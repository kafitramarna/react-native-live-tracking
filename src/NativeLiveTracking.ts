/**
 * Codegen spec for react-native-live-tracking.
 *
 * Uses TurboModuleRegistry.get (not getEnforcing) so that a missing or
 * not-yet-registered module returns null instead of throwing synchronously.
 * A top-level throw would corrupt the module graph and leave upstream
 * importers (TrackingProvider, AppNavigator) with undefined module records.
 *
 * File must be named Native*.ts and live in the codegenConfig.jsSrcsDir
 * for RN's codegen to pick it up.
 */

import type { TurboModule } from 'react-native';
import { TurboModuleRegistry, NativeModules } from 'react-native';

export interface Spec extends TurboModule {
  configure(config: string): Promise<void>;
  start(): Promise<void>;
  stop(): Promise<void>;
  getStatus(): Promise<string>;
  getQueuedLocations(): Promise<number>;
  getQueuedLocationsByTarget(): Promise<string>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

// Try TurboModule first (new arch with codegen), fall back to Bridge (old arch / interop)
export default (TurboModuleRegistry.get<Spec>('LiveTracking') ??
  NativeModules.LiveTracking) as Spec | null;
