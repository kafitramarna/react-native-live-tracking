#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

/**
 * Objective-C bridge file for the LiveTracking Swift module.
 *
 * Uses RCT_EXTERN_MODULE to expose Swift methods to React Native.
 * Also conforms to RCTTurboModule so the module is discoverable via
 * TurboModuleRegistry in New Architecture / Bridgeless mode (RN 0.76+).
 */
@interface RCT_EXTERN_MODULE(LiveTracking, RCTEventEmitter)

RCT_EXTERN_METHOD(configure:(NSString *)config
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(start:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(stop:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(getStatus:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(getQueuedLocations:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(getQueuedLocationsByTarget:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

+ (BOOL)requiresMainQueueSetup
{
    return NO;
}

@end
