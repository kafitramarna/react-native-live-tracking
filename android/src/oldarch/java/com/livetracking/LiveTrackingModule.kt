package com.livetracking

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import com.facebook.react.module.annotations.ReactModule

/**
 * Old Architecture (Bridge) implementation.
 * This file is only compiled when the old architecture is used.
 *
 * Extends ReactContextBaseJavaModule and delegates all logic to LiveTrackingModuleImpl.
 * Exposes methods: configure, start, stop, getStatus, getQueuedLocations.
 * Events are emitted directly from LiveTrackingModuleImpl via RCTDeviceEventEmitter.
 *
 * Requirements: 9.1, 9.2, 9.3, 9.4, 11.2
 */
@ReactModule(name = LiveTrackingModuleImpl.NAME)
class LiveTrackingModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val impl = LiveTrackingModuleImpl(reactContext)

    companion object {
        const val NAME = LiveTrackingModuleImpl.NAME
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun configure(config: String, promise: Promise) {
        impl.configure(config, promise)
    }

    @ReactMethod
    fun start(promise: Promise) {
        impl.start(promise)
    }

    @ReactMethod
    fun stop(promise: Promise) {
        impl.stop(promise)
    }

    @ReactMethod
    fun getStatus(promise: Promise) {
        impl.getStatus(promise)
    }

    @ReactMethod
    fun getQueuedLocations(promise: Promise) {
        impl.getQueuedLocations(promise)
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for RN event emitter - no-op
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for RN event emitter - no-op
    }
}
