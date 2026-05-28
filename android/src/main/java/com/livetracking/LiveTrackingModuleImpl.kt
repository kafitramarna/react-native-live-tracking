package com.livetracking

import android.location.Location
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.Promise
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.livetracking.location.LocationEngine
import com.livetracking.network.NetworkListener
import com.livetracking.network.NetworkStateListener
import com.livetracking.optimizer.ActivityRecognitionHandler
import com.livetracking.optimizer.MotionSleepManager
import com.livetracking.permissions.PermissionHandler
import com.livetracking.permissions.PermissionResult
import com.livetracking.queue.QueueEngine
import com.livetracking.receiver.TrackingStateStore
import com.livetracking.service.TrackingForegroundService
import com.livetracking.sync.FirebaseSyncEngine
import com.livetracking.sync.SyncCallback
import org.json.JSONObject

/**
 * Shared implementation for both old and new architecture.
 * Contains the actual business logic that is shared between Bridge and TurboModule.
 *
 * Wires all engines together:
 * - LocationEngine: GPS location updates via FusedLocationProvider
 * - QueueEngine: Offline queue (Room database)
 * - FirebaseSyncEngine: Firebase writes (RTDB or Firestore)
 * - NetworkListener: Connectivity monitoring
 * - ActivityRecognitionHandler: Activity detection
 * - MotionSleepManager: Battery optimization via motion sleep
 * - PermissionHandler: Permission checks
 * - TrackingForegroundService: Foreground service for background tracking
 *
 * Requirements: 9.1, 9.2, 9.3, 9.4, 11.2
 */
class LiveTrackingModuleImpl(private val reactContext: ReactApplicationContext) {

    companion object {
        const val NAME = "LiveTracking"

        // Event names emitted to JavaScript
        const val EVENT_LOCATION_UPDATE = "onLocationUpdate"
        const val EVENT_TRACKING_ERROR = "onTrackingError"

        // Default configuration values
        private const val DEFAULT_INTERVAL_MS = 10000L
        private const val DEFAULT_DISTANCE_FILTER_METERS = 10f
        private const val DEFAULT_STOP_WHEN_STILL = true
        private const val DEFAULT_HISTORY_BATCH_SIZE = 15

        // Tracking states
        private const val STATE_IDLE = "idle"
        private const val STATE_CONFIGURED = "configured"
        private const val STATE_TRACKING = "tracking"
        private const val STATE_MOTION_SLEEP = "motion_sleep"
    }

    // Engines
    private var locationEngine: LocationEngine? = null
    private var queueEngine: QueueEngine? = null
    private var syncEngine: FirebaseSyncEngine? = null
    private var networkListener: NetworkListener? = null
    private var activityRecognitionHandler: ActivityRecognitionHandler? = null
    private var motionSleepManager: MotionSleepManager? = null
    private val permissionHandler = PermissionHandler()

    // Configuration
    private var intervalMs: Long = DEFAULT_INTERVAL_MS
    private var distanceFilterMeters: Float = DEFAULT_DISTANCE_FILTER_METERS
    private var stopWhenStill: Boolean = DEFAULT_STOP_WHEN_STILL
    private var historyBatchSize: Int = DEFAULT_HISTORY_BATCH_SIZE
    private var firebaseService: String? = null
    private var currentLocationPath: String? = null
    private var historyPath: String? = null
    private var notificationTitle: String = "Live Tracking"
    private var notificationText: String = "Tracking your location"
    private var notificationIcon: String? = null
    private var notificationChannelId: String = TrackingForegroundService.DEFAULT_CHANNEL_ID
    private var notificationChannelName: String = TrackingForegroundService.DEFAULT_CHANNEL_NAME

    // State
    private var trackingState: String = STATE_IDLE
    private var lastLocation: Location? = null
    private var lastUpdateTimestamp: Long = 0L

    /**
     * Parse JSON config string and create all engines with config values.
     * Validates required fields and applies defaults for optional ones.
     *
     * @param config JSON string containing TrackingConfig
     * @param promise Promise to resolve/reject
     */
    fun configure(config: String, promise: Promise) {
        try {
            val json = JSONObject(config)

            // Parse optimization config
            val optimization = json.optJSONObject("optimization")
            if (optimization != null) {
                intervalMs = optimization.optLong("intervalMs", DEFAULT_INTERVAL_MS)
                distanceFilterMeters = optimization.optDouble("distanceFilterMeters", DEFAULT_DISTANCE_FILTER_METERS.toDouble()).toFloat()
                stopWhenStill = optimization.optBoolean("stopWhenStill", DEFAULT_STOP_WHEN_STILL)
            }

            // Validate optimization values
            if (intervalMs <= 0) {
                promise.reject("INVALID_CONFIG", "optimization.intervalMs must be greater than 0")
                return
            }
            if (distanceFilterMeters <= 0) {
                promise.reject("INVALID_CONFIG", "optimization.distanceFilterMeters must be greater than 0")
                return
            }

            // Parse firebase config
            val firebase = json.optJSONObject("firebase")
            if (firebase == null) {
                promise.reject("INVALID_CONFIG", "firebase configuration is required")
                return
            }

            firebaseService = firebase.optString("service", "")
            if (firebaseService != "RTDB" && firebaseService != "Firestore") {
                promise.reject("INVALID_CONFIG", "firebase.service must be 'RTDB' or 'Firestore'")
                return
            }

            currentLocationPath = firebase.optString("currentLocationPath", "").ifEmpty { null }
            historyPath = firebase.optString("historyPath", "").ifEmpty { null }
            historyBatchSize = firebase.optInt("historyBatchSize", DEFAULT_HISTORY_BATCH_SIZE)

            if (currentLocationPath == null && historyPath == null) {
                promise.reject("INVALID_CONFIG", "At least one of firebase.currentLocationPath or firebase.historyPath must be configured")
                return
            }

            // Parse Android notification config
            val androidNotification = json.optJSONObject("androidNotification")
            if (androidNotification != null) {
                notificationTitle = androidNotification.optString("title", "Live Tracking")
                notificationText = androidNotification.optString("text", "Tracking your location")
                notificationIcon = androidNotification.optString("icon", "").ifEmpty { null }
                notificationChannelId = androidNotification.optString("channelId", TrackingForegroundService.DEFAULT_CHANNEL_ID)
                notificationChannelName = androidNotification.optString("channelName", TrackingForegroundService.DEFAULT_CHANNEL_NAME)
            }

            // Initialize engines
            initializeEngines()

            trackingState = STATE_CONFIGURED
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("INVALID_CONFIG", "Failed to parse configuration: ${e.message}")
        }
    }

    /**
     * Start tracking: check permissions, start foreground service, start location engine,
     * start activity recognition, start network listener, save tracking state.
     *
     * @param promise Promise to resolve/reject
     */
    fun start(promise: Promise) {
        if (trackingState == STATE_IDLE) {
            promise.reject("NOT_CONFIGURED", "Call configure() before start()")
            return
        }

        if (trackingState == STATE_TRACKING || trackingState == STATE_MOTION_SLEEP) {
            promise.resolve(null) // Already tracking
            return
        }

        // Check permissions
        val permissionResult = permissionHandler.checkAllTrackingRequirements(reactContext)
        if (permissionResult is PermissionResult.Denied) {
            promise.reject(permissionResult.errorCode, permissionResult.message)
            emitError(permissionResult.errorCode, permissionResult.message)
            return
        }

        try {
            // Start foreground service
            TrackingForegroundService.start(
                context = reactContext,
                title = notificationTitle,
                text = notificationText,
                icon = notificationIcon,
                channelId = notificationChannelId,
                channelName = notificationChannelName
            )

            // Start location engine
            locationEngine?.startLocationUpdates(intervalMs, distanceFilterMeters)

            // Start activity recognition (for motion sleep mode)
            if (stopWhenStill) {
                try {
                    activityRecognitionHandler?.startActivityRecognition()
                } catch (e: SecurityException) {
                    // Activity recognition permission not granted, continue without it
                }
            }

            // Start network listener
            networkListener?.startListening()

            // Save tracking state for boot receiver
            TrackingStateStore.saveTrackingActive(reactContext, true)

            trackingState = STATE_TRACKING
            promise.resolve(null)
        } catch (e: SecurityException) {
            promise.reject("PERMISSION_DENIED", "Location permission denied: ${e.message}")
            emitError("PERMISSION_DENIED", "Location permission denied: ${e.message}")
        } catch (e: Exception) {
            promise.reject("SERVICE_UNAVAILABLE", "Failed to start tracking: ${e.message}")
            emitError("SERVICE_UNAVAILABLE", "Failed to start tracking: ${e.message}")
        }
    }

    /**
     * Stop all engines, stop foreground service, save tracking state as inactive.
     *
     * @param promise Promise to resolve/reject
     */
    fun stop(promise: Promise) {
        try {
            // Stop location engine
            locationEngine?.stopLocationUpdates()

            // Stop activity recognition
            activityRecognitionHandler?.stopActivityRecognition()

            // Stop network listener
            networkListener?.stopListening()

            // Stop foreground service
            TrackingForegroundService.stop(reactContext)

            // Save tracking state as inactive
            TrackingStateStore.saveTrackingActive(reactContext, false)

            // Reset state
            trackingState = STATE_CONFIGURED
            lastLocation = null
            lastUpdateTimestamp = 0L

            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("STOP_FAILED", "Failed to stop tracking: ${e.message}")
        }
    }

    /**
     * Return JSON with state, isOnline, queuedLocations, lastLocation, batteryOptimization.
     *
     * @param promise Promise to resolve with JSON string
     */
    fun getStatus(promise: Promise) {
        try {
            val status = JSONObject()
            status.put("state", trackingState)
            status.put("isOnline", networkListener?.isOnline() ?: true)

            // Get queued locations count
            val queuedCount = try {
                queueEngine?.count()?.get() ?: 0
            } catch (e: Exception) {
                0
            }
            status.put("queuedLocations", queuedCount)

            // Last location
            val last = lastLocation
            if (last != null) {
                val locationJson = JSONObject()
                locationJson.put("latitude", last.latitude)
                locationJson.put("longitude", last.longitude)
                locationJson.put("timestamp", last.time)
                locationJson.put("accuracy", last.accuracy.toDouble())
                locationJson.put("speed", if (last.hasSpeed()) last.speed.toDouble() else JSONObject.NULL)
                locationJson.put("altitude", if (last.hasAltitude()) last.altitude else JSONObject.NULL)
                locationJson.put("bearing", if (last.hasBearing()) last.bearing.toDouble() else JSONObject.NULL)
                status.put("lastLocation", locationJson)
            } else {
                status.put("lastLocation", JSONObject.NULL)
            }

            // Battery optimization state
            val batteryOptimization = when {
                !stopWhenStill -> "disabled"
                motionSleepManager?.isInSleepMode() == true -> "low_power"
                else -> "full_accuracy"
            }
            status.put("batteryOptimization", batteryOptimization)

            promise.resolve(status.toString())
        } catch (e: Exception) {
            promise.reject("STATUS_ERROR", "Failed to get status: ${e.message}")
        }
    }

    /**
     * Return the count of queued locations waiting to be synced.
     *
     * @param promise Promise to resolve with queue count
     */
    fun getQueuedLocations(promise: Promise) {
        try {
            val count = queueEngine?.count()?.get() ?: 0
            promise.resolve(count)
        } catch (e: Exception) {
            promise.resolve(0)
        }
    }

    // --- Private Implementation ---

    /**
     * Initialize all engines with the current configuration.
     */
    private fun initializeEngines() {
        // Location Engine
        locationEngine = LocationEngine(reactContext).apply {
            setLocationListener(object : LocationEngine.LocationUpdateListener {
                override fun onLocationReceived(location: Location) {
                    handleLocationUpdate(location)
                }
            })
        }

        // Queue Engine
        queueEngine = QueueEngine(reactContext)

        // Firebase Sync Engine
        syncEngine = FirebaseSyncEngine(
            service = firebaseService!!,
            currentLocationPath = currentLocationPath,
            historyPath = historyPath
        )

        // Network Listener
        networkListener = NetworkListener(reactContext).apply {
            setNetworkStateListener(object : NetworkStateListener {
                override fun onNetworkAvailable() {
                    handleNetworkRestored()
                }

                override fun onNetworkLost() {
                    // No action needed - queue will hold data
                }
            })
        }

        // Activity Recognition Handler
        activityRecognitionHandler = ActivityRecognitionHandler(reactContext).apply {
            stillThresholdMs = MotionSleepManager.STILL_THRESHOLD_MS
            setActivityStateListener(object : ActivityRecognitionHandler.ActivityStateListener {
                override fun onActivityChanged(activity: ActivityRecognitionHandler.ActivityType) {
                    handleActivityChanged(activity)
                }

                override fun onStillDurationExceeded(durationMs: Long) {
                    // MotionSleepManager handles this via onActivityDetected
                }
            })
        }

        // Motion Sleep Manager
        motionSleepManager = MotionSleepManager(
            locationEngine = locationEngine!!,
            stopWhenStill = stopWhenStill,
            intervalMs = intervalMs,
            distanceFilter = distanceFilterMeters
        ).apply {
            setListener(object : MotionSleepManager.MotionSleepListener {
                override fun onSleepModeActivated() {
                    trackingState = STATE_MOTION_SLEEP
                }

                override fun onSleepModeDeactivated() {
                    trackingState = STATE_TRACKING
                }
            })
        }
    }

    /**
     * Handle a new location update from LocationEngine.
     * Applies distance/time filter, emits event to JS, enqueues for history, syncs current location.
     */
    private fun handleLocationUpdate(location: Location) {
        // Apply distance/time filter
        if (!shouldAcceptLocation(location)) {
            return
        }

        // Update last location and timestamp
        lastLocation = location
        lastUpdateTimestamp = System.currentTimeMillis()

        // Emit location event to JavaScript
        emitLocationUpdate(location)

        // Enqueue for history (if historyPath configured)
        if (historyPath != null) {
            queueEngine?.enqueue(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = location.time,
                accuracy = location.accuracy,
                speed = if (location.hasSpeed()) location.speed else null,
                altitude = if (location.hasAltitude()) location.altitude else null,
                bearing = if (location.hasBearing()) location.bearing else null
            )

            // Check if batch size reached, trigger sync
            checkAndFlushBatch()
        }

        // Sync current location to Firebase (if currentLocationPath configured)
        if (currentLocationPath != null) {
            syncCurrentLocation(location)
        }
    }

    /**
     * Distance/Time Matrix filter.
     * Accepts location only if BOTH conditions are met:
     * - Time since last update >= intervalMs
     * - Distance from last location >= distanceFilterMeters
     *
     * First location is always accepted.
     */
    private fun shouldAcceptLocation(newLocation: Location): Boolean {
        val last = lastLocation ?: return true // First location always accepted

        // Check time condition
        val timeDiff = System.currentTimeMillis() - lastUpdateTimestamp
        if (timeDiff < intervalMs) {
            return false
        }

        // Check distance condition
        val distance = last.distanceTo(newLocation)
        if (distance < distanceFilterMeters) {
            return false
        }

        return true
    }

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

    /**
     * Sync current location to Firebase (overwrite at currentLocationPath).
     */
    private fun syncCurrentLocation(location: Location) {
        syncEngine?.updateCurrentLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = location.time,
            accuracy = location.accuracy,
            speed = if (location.hasSpeed()) location.speed else null,
            callback = object : SyncCallback {
                override fun onSuccess() {
                    // Successfully synced current location
                }

                override fun onError(errorCode: String, message: String) {
                    emitError(errorCode, message)
                }
            }
        )
    }

    /**
     * Check if batch size is reached and flush queue to Firebase history.
     */
    private fun checkAndFlushBatch() {
        try {
            val count = queueEngine?.count()?.get() ?: 0
            if (count >= historyBatchSize && networkListener?.isOnline() != false) {
                flushQueueBatch()
            }
        } catch (e: Exception) {
            // Queue count failed, skip batch check
        }
    }

    /**
     * Flush a batch of queued locations to Firebase history path.
     */
    private fun flushQueueBatch() {
        try {
            val batch = queueEngine?.dequeueBatch(historyBatchSize)?.get() ?: return
            if (batch.isEmpty()) return

            syncEngine?.pushHistoryBatch(batch, object : SyncCallback {
                override fun onSuccess() {
                    // Remove successfully sent batch from queue
                    val ids = batch.map { it.id }
                    queueEngine?.removeBatch(ids)
                }

                override fun onError(errorCode: String, message: String) {
                    // Batch remains in queue for retry
                    emitError(errorCode, message)
                }
            })
        } catch (e: Exception) {
            // Queue operation failed, will retry on next opportunity
        }
    }

    /**
     * Handle network restored: flush pending queue.
     */
    private fun handleNetworkRestored() {
        if (trackingState == STATE_TRACKING || trackingState == STATE_MOTION_SLEEP) {
            flushQueueBatch()
        }
    }

    /**
     * Handle activity state change from ActivityRecognitionHandler.
     * Delegates to MotionSleepManager for sleep mode management.
     */
    private fun handleActivityChanged(activity: ActivityRecognitionHandler.ActivityType) {
        val detectedActivityType = when (activity) {
            ActivityRecognitionHandler.ActivityType.STILL ->
                com.google.android.gms.location.DetectedActivity.STILL
            ActivityRecognitionHandler.ActivityType.ON_FOOT ->
                com.google.android.gms.location.DetectedActivity.ON_FOOT
            ActivityRecognitionHandler.ActivityType.IN_VEHICLE ->
                com.google.android.gms.location.DetectedActivity.IN_VEHICLE
            ActivityRecognitionHandler.ActivityType.UNKNOWN ->
                com.google.android.gms.location.DetectedActivity.UNKNOWN
        }
        motionSleepManager?.onActivityDetected(detectedActivityType)
    }
}
