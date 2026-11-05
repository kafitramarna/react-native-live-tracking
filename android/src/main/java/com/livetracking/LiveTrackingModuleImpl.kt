package com.livetracking

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.Promise
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.livetracking.runtime.TrackingEventListener
import com.livetracking.runtime.TrackingException
import com.livetracking.runtime.TrackingRuntime

/**
 * Shared implementation for both old and new architecture.
 * Thin delegate over the process-wide TrackingRuntime singleton — all engine
 * ownership and lifecycle logic lives there so tracking survives RN teardown,
 * process death, and reboot.
 *
 * This class only handles the RN boundary:
 * - Promise resolution/rejection (maps TrackingException.errorCode)
 * - JS event emission via RCTDeviceEventEmitter
 *
 * Requirements: 3.1, 3.5, 4.4, 9.2, 9.4, 10.1, 10.2, 10.3, 10.4
 */
class LiveTrackingModuleImpl(private val reactContext: ReactApplicationContext) {

    companion object {
        const val NAME = "LiveTracking"

        // Event names emitted to JavaScript
        const val EVENT_LOCATION_UPDATE = "onLocationUpdate"
        const val EVENT_TRACKING_ERROR = "onTrackingError"
    }

    private val runtime: TrackingRuntime = TrackingRuntime.getInstance(reactContext)

    private val listener = object : TrackingEventListener {
        override fun onLocationUpdate(location: Location) {
            emitLocationUpdate(location)
        }

        override fun onError(code: String, message: String) {
            emitError(code, message)
        }
    }

    init {
        // Register as the runtime's event sink so engine events reach JS.
        // Last registration wins — relevant if the module is re-instantiated
        // (e.g. React reload). When no listener is registered, events drop silently.
        runtime.eventListener = listener
    }

    /**
     * Called when the RN context is being torn down (invalidate()). Detaches
     * this impl's listener so the dead ReactApplicationContext isn't retained
     * by the singleton runtime. Only clears when this impl is still the
     * registered listener — a newer module instance must not be detached.
     */
    fun onCatalystDestroyed() {
        if (runtime.eventListener === listener) {
            runtime.eventListener = null
        }
    }

    /**
     * Parse JSON config and initialize engines via the runtime.
     * Also persists the config for native self-heal after restart/reboot.
     */
    fun configure(config: String, promise: Promise) {
        try {
            runtime.configure(config)
            promise.resolve(null)
        } catch (e: TrackingException) {
            promise.reject(e.errorCode, e.message)
        } catch (e: Exception) {
            promise.reject("INVALID_CONFIG", "Failed to parse configuration: ${e.message}")
        }
    }

    /**
     * Start tracking: permissions check, foreground service, engines,
     * native status watcher. Idempotent when already tracking.
     */
    fun start(promise: Promise) {
        try {
            runtime.start()
            promise.resolve(null)
        } catch (e: TrackingException) {
            promise.reject(e.errorCode, e.message)
        } catch (e: SecurityException) {
            promise.reject("PERMISSION_DENIED", "Location permission denied: ${e.message}")
        } catch (e: Exception) {
            promise.reject("SERVICE_UNAVAILABLE", "Failed to start tracking: ${e.message}")
        }
    }

    /**
     * Stop all engines, disarm the status watcher, stop foreground service.
     * Flushes all partially-filled batches via SyncEngineController before stopping.
     */
    fun stop(promise: Promise) {
        try {
            runtime.stop(TrackingRuntime.STOP_REASON_MANUAL)
            promise.resolve(null)
        } catch (e: TrackingException) {
            promise.reject(e.errorCode, e.message)
        } catch (e: Exception) {
            promise.reject("STOP_FAILED", "Failed to stop tracking: ${e.message}")
        }
    }

    /**
     * Return JSON with state, isOnline, queuedLocations, lastLocation,
     * batteryOptimization, nativeWatching, and one-shot markers
     * (lastRestartReason, lastStopReason).
     */
    fun getStatus(promise: Promise) {
        try {
            promise.resolve(runtime.getStatus())
        } catch (e: TrackingException) {
            promise.reject(e.errorCode, e.message)
        } catch (e: Exception) {
            promise.reject("STATUS_ERROR", "Failed to get status: ${e.message}")
        }
    }

    /**
     * Return the total count of queued locations across all targets.
     * Rejects with NOT_CONFIGURED if called before configure().
     */
    fun getQueuedLocations(promise: Promise) {
        try {
            promise.resolve(runtime.getQueuedLocations())
        } catch (e: TrackingException) {
            promise.reject(e.errorCode, e.message)
        } catch (e: Exception) {
            promise.resolve(0)
        }
    }

    /**
     * Return a JSON string mapping each configured target path to its queued location count.
     * Targets with offlineQueue disabled report 0.
     * Rejects with NOT_CONFIGURED if called before configure().
     * Requirements: 10.2, 10.3, 10.4
     */
    fun getQueuedLocationsByTarget(promise: Promise) {
        try {
            promise.resolve(runtime.getQueuedLocationsByTarget())
        } catch (e: TrackingException) {
            promise.reject(e.errorCode, e.message)
        } catch (e: Exception) {
            promise.reject("STATUS_ERROR", "Failed to get queued locations by target: ${e.message}")
        }
    }

    /**
     * Whether the app is exempted from battery optimization (Doze whitelist).
     * Apps not on the whitelist may have location delivery deferred while idle.
     */
    fun isIgnoringBatteryOptimizations(promise: Promise) {
        try {
            val pm = reactContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            promise.resolve(pm?.isIgnoringBatteryOptimizations(reactContext.packageName) ?: false)
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    /**
     * Open the system battery-optimization settings so the user can exempt the
     * app manually. Falls back to the app details page when the dedicated
     * screen is unavailable on this device.
     */
    fun openBatteryOptimizationSettings(promise: Promise) {
        try {
            reactContext.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            promise.resolve(null)
        } catch (e: ActivityNotFoundException) {
            try {
                reactContext.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${reactContext.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                promise.resolve(null)
            } catch (e2: Exception) {
                promise.reject("SETTINGS_UNAVAILABLE", "Cannot open settings: ${e2.message}")
            }
        } catch (e: Exception) {
            promise.reject("SETTINGS_UNAVAILABLE", "Cannot open settings: ${e.message}")
        }
    }

    // --- Private Implementation (JS event emission only) ---

    /**
     * Emit location update event to JavaScript layer.
     */
    private fun emitLocationUpdate(location: Location) {
        try {
            val params = Arguments.createMap().apply {
                putDouble("latitude", location.latitude)
                putDouble("longitude", location.longitude)
                putDouble("timestamp", location.time.toDouble())
                putDouble("accuracy", location.accuracy.toDouble())
                putDouble("speed", if (location.hasSpeed()) location.speed.toDouble() else 0.0)
                putDouble("altitude", if (location.hasAltitude()) location.altitude else 0.0)
                putDouble("bearing", if (location.hasBearing()) location.bearing.toDouble() else 0.0)
            }

            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(EVENT_LOCATION_UPDATE, params)
        } catch (e: Exception) {
            // JS module may not be available yet, ignore
        }
    }

    /**
     * Emit error event to JavaScript layer.
     */
    private fun emitError(code: String, message: String) {
        try {
            val params = Arguments.createMap().apply {
                putString("code", code)
                putString("message", message)
            }

            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(EVENT_TRACKING_ERROR, params)
        } catch (e: Exception) {
            // JS module may not be available yet, ignore
        }
    }
}
