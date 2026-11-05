package com.livetracking.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import com.livetracking.sync.LocationDataPoint
import com.livetracking.sync.SyncEngineController
import com.livetracking.sync.TargetEventListener
import org.json.JSONObject

/**
 * Exception carrying a JS-facing error code so the module can map it to a
 * promise rejection without losing the original code.
 */
class TrackingException(val errorCode: String, message: String) : Exception(message)

/**
 * Listener for events emitted by the runtime. The RN module registers itself
 * here; when no listener is registered (RN context dead, service-only process),
 * events are silently dropped — same as the previous try/catch emit behavior.
 */
interface TrackingEventListener {
    fun onLocationUpdate(location: Location)
    fun onError(code: String, message: String)
}

/**
 * TrackingRuntime owns all tracking engines and lifecycle state, decoupled from
 * the React context. It lives as a process-wide singleton on the application
 * context so that:
 *
 * - The foreground service can self-heal: rebuild engines from the persisted
 *   config after START_STICKY restarts and reboots (no zombie notification).
 * - The native StatusWatcher can stop tracking on remote status changes even
 *   when the RN/JS layer is dead.
 *
 * The RN module (LiveTrackingModuleImpl) is a thin delegate over this runtime.
 *
 * All public methods are @Synchronized: they may be invoked from the RN native
 * module thread, the main thread (service callbacks), or Firebase listener
 * threads (StatusWatcher).
 */
class TrackingRuntime private constructor(private val appContext: Context) {

    companion object {
        // Default configuration values
        private const val DEFAULT_INTERVAL_MS = 10000L
        private const val DEFAULT_DISTANCE_FILTER_METERS = 10f
        private const val DEFAULT_STOP_WHEN_STILL = true
        private const val DEFAULT_OPTIMIZATION_MODE = "both"

        // Tracking states
        const val STATE_IDLE = "idle"
        const val STATE_CONFIGURED = "configured"
        const val STATE_TRACKING = "tracking"
        const val STATE_MOTION_SLEEP = "motion_sleep"
        const val STATE_PAUSED_GPS = "paused_gps"

        // Error codes
        const val ERROR_GPS_DISABLED = "GPS_DISABLED"
        const val ERROR_GPS_ENABLED = "GPS_ENABLED"
        const val ERROR_PERMISSION_REVOKED = "PERMISSION_REVOKED"
        const val ERROR_LOCATION_STALE_RECOVERY = "LOCATION_STALE_RECOVERY"

        // Stale-location watchdog: re-request location updates when the OS
        // stops delivering fixes (doze / vendor power management) while the
        // tracking state still believes it is running.
        private const val STALE_THRESHOLD_MULTIPLIER = 3L
        private const val STALE_MIN_THRESHOLD_MS = 30000L

        // Stop reasons
        const val STOP_REASON_MANUAL = "manual"
        const val STOP_REASON_REMOTE_STATUS = "remote_status"
        const val STOP_REASON_PERMISSION_REVOKED = "permission_revoked"
        const val STOP_REASON_RESUME_FAILED = "resume_failed"
        const val STOP_REASON_RESUME_DISABLED = "resume_disabled"

        @Volatile
        private var instance: TrackingRuntime? = null

        fun getInstance(context: Context): TrackingRuntime {
            return instance ?: synchronized(this) {
                instance ?: TrackingRuntime(context.applicationContext).also { instance = it }
            }
        }
    }

    enum class ServiceStartResult {
        /** Engines were rebuilt and tracking resumed (self-heal). */
        RESUMED,
        /** Tracking is already running in this process. */
        ALREADY_TRACKING,
        /** Nothing persisted to resume — service started cold. */
        NOTHING_TO_RESUME,
        /** A resume was needed but failed — caller should stopSelf(). */
        RESUME_FAILED
    }

    // Event sink registered by the RN module; null-safe when RN is dead
    var eventListener: TrackingEventListener? = null

    // Engines
    private var locationEngine: LocationEngine? = null
    private var queueEngine: QueueEngine? = null
    private var syncEngineController: SyncEngineController? = null
    private var networkListener: NetworkListener? = null
    private var activityRecognitionHandler: ActivityRecognitionHandler? = null
    private var motionSleepManager: MotionSleepManager? = null
    private var statusWatcher: StatusWatcher? = null
    private val permissionHandler = PermissionHandler()

    // Configuration
    private var intervalMs: Long = DEFAULT_INTERVAL_MS
    private var distanceFilterMeters: Float = DEFAULT_DISTANCE_FILTER_METERS
    private var stopWhenStill: Boolean = DEFAULT_STOP_WHEN_STILL
    private var optimizationMode: String = DEFAULT_OPTIMIZATION_MODE
    private var firebaseService: String? = null
    private var notificationEnabled: Boolean = true
    private var notificationTitle: String = "Live Tracking"
    private var notificationText: String = "Tracking your location"
    private var notificationIcon: String? = null
    private var notificationChannelId: String = TrackingForegroundService.DEFAULT_CHANNEL_ID
    private var notificationChannelName: String = TrackingForegroundService.DEFAULT_CHANNEL_NAME
    private var iosNotificationEnabled: Boolean = true
    private var wakeLockEnabled: Boolean = true

    // Control (native status watcher + self-heal) configuration
    private var controlStatusPath: String? = null
    private var controlStopValue: Int = 2
    private var controlResumeOnRestart: Boolean = true

    // State
    private var trackingState: String = STATE_IDLE
    private var lastLocation: Location? = null
    private var lastUpdateTimestamp: Long = 0L
    private var wasTrackingBeforeGpsDisabled: Boolean = false
    private var gpsStatusReceiver: BroadcastReceiver? = null

    // Stale-location watchdog
    private var staleHandler: Handler? = null
    private var staleRunnable: Runnable? = null
    private var lastStaleRecoveryAt: Long = 0L
    private var staleRecoveries: Int = 0

    /**
     * Parse JSON config and create all engines with config values.
     * On success, persists the raw config so engines can be rebuilt natively
     * after process death/reboot.
     *
     * @throws TrackingException with INVALID_CONFIG on bad config
     */
    @Synchronized
    fun configure(config: String) {
        try {
            val json = JSONObject(config)

            // Parse optimization config
            val optimization = json.optJSONObject("optimization")
            if (optimization != null) {
                intervalMs = optimization.optLong("intervalMs", DEFAULT_INTERVAL_MS)
                distanceFilterMeters = optimization.optDouble("distanceFilterMeters", DEFAULT_DISTANCE_FILTER_METERS.toDouble()).toFloat()
                stopWhenStill = optimization.optBoolean("stopWhenStill", DEFAULT_STOP_WHEN_STILL)
                optimizationMode = optimization.optString("mode", DEFAULT_OPTIMIZATION_MODE)
            }

            // Validate optimization values
            if (intervalMs <= 0) {
                throw TrackingException("INVALID_CONFIG", "optimization.intervalMs must be greater than 0")
            }
            if (distanceFilterMeters <= 0) {
                throw TrackingException("INVALID_CONFIG", "optimization.distanceFilterMeters must be greater than 0")
            }
            if (optimizationMode != "interval" && optimizationMode != "distance" && optimizationMode != "both") {
                throw TrackingException("INVALID_CONFIG", "optimization.mode must be 'interval', 'distance', or 'both'")
            }

            // Parse firebase config
            val firebase = json.optJSONObject("firebase")
                ?: throw TrackingException("INVALID_CONFIG", "firebase configuration is required")

            firebaseService = firebase.optString("service", "")
            if (firebaseService != "RTDB" && firebaseService != "Firestore") {
                throw TrackingException("INVALID_CONFIG", "firebase.service must be 'RTDB' or 'Firestore'")
            }

            // Parse targets array from firebase config
            val targetsArray = firebase.optJSONArray("targets")
            if (targetsArray == null || targetsArray.length() == 0) {
                throw TrackingException("INVALID_CONFIG", "firebase.targets array is required and must contain at least one sync target")
            }

            val targetsJson = targetsArray.toString()

            // Parse Android notification config
            val androidNotification = json.optJSONObject("androidNotification")
            if (androidNotification != null) {
                notificationEnabled = androidNotification.optBoolean("enabled", true)
                notificationTitle = androidNotification.optString("title", "Live Tracking")
                notificationText = androidNotification.optString("text", "Tracking your location")
                notificationIcon = androidNotification.optString("icon", "").ifEmpty { null }
                notificationChannelId = androidNotification.optString("channelId", TrackingForegroundService.DEFAULT_CHANNEL_ID)
                notificationChannelName = androidNotification.optString("channelName", TrackingForegroundService.DEFAULT_CHANNEL_NAME)
            }

            // Parse Android platform config
            val androidConfig = json.optJSONObject("android")
            if (androidConfig != null) {
                wakeLockEnabled = androidConfig.optBoolean("wakeLock", true)
            }

            // Parse iOS notification config
            val iosNotification = json.optJSONObject("iosNotification")
            if (iosNotification != null) {
                iosNotificationEnabled = iosNotification.optBoolean("enabled", true)
            }

            // Parse control config (native status watcher + self-heal)
            val control = json.optJSONObject("control")
            if (control != null) {
                controlStatusPath = control.optString("statusPath", "").ifEmpty { null }
                controlStopValue = control.optInt("stopValue", 2)
                controlResumeOnRestart = control.optBoolean("resumeOnRestart", true)
            }

            // Initialize engines (including SyncEngineController with targets)
            initializeEngines(targetsJson)

            // Persist config so engines can be rebuilt without the JS layer
            TrackingStateStore.saveConfig(appContext, config)

            trackingState = STATE_CONFIGURED
        } catch (e: TrackingException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // SyncEngineController throws IllegalArgumentException for invalid targets JSON
            throw TrackingException("INVALID_CONFIG", "Failed to parse targets configuration: ${e.message}")
        } catch (e: Exception) {
            throw TrackingException("INVALID_CONFIG", "Failed to parse configuration: ${e.message}")
        }
    }

    /**
     * Start tracking: check permissions, start foreground service, start location
     * engine, arm the native status watcher, persist tracking state.
     *
     * Idempotent: returns normally if already tracking.
     *
     * @throws TrackingException with NOT_CONFIGURED, permission error code,
     *         PERMISSION_DENIED, or SERVICE_UNAVAILABLE
     */
    @Synchronized
    fun start() {
        if (trackingState == STATE_IDLE) {
            throw TrackingException("NOT_CONFIGURED", "Call configure() before start()")
        }

        if (trackingState == STATE_TRACKING || trackingState == STATE_MOTION_SLEEP) {
            return // Already tracking
        }

        // Check permissions
        val permissionResult = permissionHandler.checkAllTrackingRequirements(appContext)
        if (permissionResult is PermissionResult.Denied) {
            emitError(permissionResult.errorCode, permissionResult.message)
            throw TrackingException(permissionResult.errorCode, permissionResult.message)
        }

        try {
            // Start foreground service (always required for background tracking on Android)
            val foregroundTitle = if (notificationEnabled) notificationTitle else "Live Tracking"
            val foregroundText = if (notificationEnabled) notificationText else "Tracking your location"
            TrackingForegroundService.start(
                context = appContext,
                title = foregroundTitle,
                text = foregroundText,
                icon = if (notificationEnabled) notificationIcon else null,
                channelId = notificationChannelId,
                channelName = notificationChannelName,
                wakeLock = wakeLockEnabled
            )

            // Start tracking engines
            startLocationTracking()

            // Monitor GPS status changes so we can pause/resume automatically
            registerGpsStatusListener()

            // Save tracking state for boot receiver / self-heal
            TrackingStateStore.saveTrackingActive(appContext, true)

            // Arm native status watcher so remote stop signals work without RN
            armStatusWatcher()

            trackingState = STATE_TRACKING
        } catch (e: SecurityException) {
            emitError("PERMISSION_DENIED", "Location permission denied: ${e.message}")
            throw TrackingException("PERMISSION_DENIED", "Location permission denied: ${e.message}")
        } catch (e: Exception) {
            emitError("SERVICE_UNAVAILABLE", "Failed to start tracking: ${e.message}")
            throw TrackingException("SERVICE_UNAVAILABLE", "Failed to start tracking: ${e.message}")
        }
    }

    /**
     * Stop all engines, disarm the watcher, stop foreground service, and persist
     * tracking state as inactive. Flushes all partially-filled batches first.
     * Idempotent — safe to call from both the JS path and the native watcher.
     *
     * @param reason recorded as a one-shot marker readable via getStatus()
     *        (e.g. "manual", "remote_status")
     * @throws TrackingException with STOP_FAILED on error
     */
    @Synchronized
    fun stop(reason: String = STOP_REASON_MANUAL) {
        try {
            // Only the caller that actually transitioned out of tracking gets to
            // record the reason — a later idempotent stop() must not overwrite it
            val wasTracking = trackingState == STATE_TRACKING ||
                trackingState == STATE_MOTION_SLEEP ||
                trackingState == STATE_PAUSED_GPS

            // Disarm native watcher first so no further remote stops can fire
            statusWatcher?.disarm()

            // Flush all partially-filled batches before stopping (Requirement 4.4)
            syncEngineController?.flushAll()

            // Stop tracking engines
            stopLocationTracking()

            // Stop foreground service
            TrackingForegroundService.stop(appContext)

            // Unregister GPS status listener
            unregisterGpsStatusListener()

            // Save tracking state as inactive + record stop reason
            TrackingStateStore.saveTrackingActive(appContext, false)
            if (wasTracking) {
                TrackingStateStore.saveLastStopReason(appContext, reason)
            }

            // Reset state
            trackingState = STATE_CONFIGURED
            lastLocation = null
            lastUpdateTimestamp = 0L
            wasTrackingBeforeGpsDisabled = false
        } catch (e: Exception) {
            throw TrackingException("STOP_FAILED", "Failed to stop tracking: ${e.message}")
        }
    }

    /**
     * Called by TrackingForegroundService when it (re)starts. Self-heals a
     * tracking session that was interrupted by process death or reboot:
     * rebuilds engines from the persisted config, resumes them, and re-arms
     * the status watcher — all without the RN/JS layer.
     *
     * @param reason "explicit" | "boot" | "sticky_restart"
     * @return ServiceStartResult describing what happened
     */
    @Synchronized
    fun onServiceStarted(reason: String): ServiceStartResult {
        if (reason != "explicit") {
            TrackingStateStore.saveLastRestartReason(appContext, reason)
        }

        // Tracking already running in this process — nothing to heal
        if (trackingState == STATE_TRACKING || trackingState == STATE_MOTION_SLEEP || trackingState == STATE_PAUSED_GPS) {
            return ServiceStartResult.ALREADY_TRACKING
        }

        if (!TrackingStateStore.isTrackingActive(appContext)) {
            return ServiceStartResult.NOTHING_TO_RESUME
        }

        if (!controlResumeOnRestart) {
            TrackingStateStore.saveTrackingActive(appContext, false)
            TrackingStateStore.saveLastStopReason(appContext, STOP_REASON_RESUME_DISABLED)
            return ServiceStartResult.RESUME_FAILED
        }

        // Rebuild engines from the persisted config
        val savedConfig = TrackingStateStore.loadConfig(appContext)
        if (savedConfig == null) {
            TrackingStateStore.saveTrackingActive(appContext, false)
            TrackingStateStore.saveLastStopReason(appContext, STOP_REASON_RESUME_FAILED)
            return ServiceStartResult.RESUME_FAILED
        }

        try {
            configure(savedConfig)
        } catch (e: Exception) {
            TrackingStateStore.saveTrackingActive(appContext, false)
            TrackingStateStore.saveLastStopReason(appContext, STOP_REASON_RESUME_FAILED)
            return ServiceStartResult.RESUME_FAILED
        }

        // Resume requires the same permissions as start()
        val permissionResult = permissionHandler.checkAllTrackingRequirements(appContext)
        if (permissionResult is PermissionResult.Denied) {
            TrackingStateStore.saveTrackingActive(appContext, false)
            TrackingStateStore.saveLastStopReason(appContext, STOP_REASON_PERMISSION_REVOKED)
            emitError(permissionResult.errorCode, permissionResult.message)
            return ServiceStartResult.RESUME_FAILED
        }

        return try {
            startLocationTracking()
            registerGpsStatusListener()
            armStatusWatcher()
            trackingState = STATE_TRACKING
            ServiceStartResult.RESUMED
        } catch (e: Exception) {
            TrackingStateStore.saveTrackingActive(appContext, false)
            TrackingStateStore.saveLastStopReason(appContext, STOP_REASON_RESUME_FAILED)
            ServiceStartResult.RESUME_FAILED
        }
    }

    /**
     * Called by TrackingForegroundService.onDestroy. A destroy while tracking is
     * active is anomalous (service killed outside the normal stop path) — record
     * a marker so the JS layer can report it on next launch.
     */
    @Synchronized
    fun onServiceDestroyed() {
        if (trackingState == STATE_TRACKING || trackingState == STATE_MOTION_SLEEP) {
            TrackingStateStore.saveLastRestartReason(appContext, "service_destroyed_while_tracking")
        }
    }

    /**
     * Return JSON with state, isOnline, queuedLocations, lastLocation,
     * batteryOptimization, nativeWatching, and one-shot observability markers
     * (lastRestartReason, lastStopReason — each cleared after first read).
     */
    @Synchronized
    fun getStatus(): String {
        try {
            val status = JSONObject()
            status.put("state", trackingState)
            status.put("isOnline", networkListener?.isOnline() ?: true)

            // Get queued locations count from SyncEngineController (sum of all targets)
            val queuedCount = syncEngineController?.getQueuedCounts()?.values?.sum() ?: 0
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

            // Whether the app is exempted from battery optimization
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            status.put("batteryOptimizationExempt", powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) ?: false)
            status.put("wakeLock", wakeLockEnabled)

            // Stale-location watchdog diagnostics
            status.put("lastFixAt", if (lastUpdateTimestamp > 0) lastUpdateTimestamp else JSONObject.NULL)
            status.put("staleRecoveries", staleRecoveries)

            // Native status watcher state
            status.put("nativeWatching", statusWatcher?.isArmed == true)

            // One-shot observability markers (consumed on read)
            val lastRestart = TrackingStateStore.consumeLastRestartReason(appContext)
            status.put("lastRestartReason", lastRestart ?: JSONObject.NULL)
            val lastStop = TrackingStateStore.consumeLastStopReason(appContext)
            status.put("lastStopReason", lastStop ?: JSONObject.NULL)

            return status.toString()
        } catch (e: Exception) {
            throw TrackingException("STATUS_ERROR", "Failed to get status: ${e.message}")
        }
    }

    /**
     * Return the total count of queued locations across all targets.
     * @throws TrackingException with NOT_CONFIGURED if called before configure()
     */
    @Synchronized
    fun getQueuedLocations(): Int {
        if (trackingState == STATE_IDLE) {
            throw TrackingException("NOT_CONFIGURED", "Call configure() before getQueuedLocations()")
        }
        return try {
            syncEngineController?.getQueuedCounts()?.values?.sum() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Return a JSON string mapping each configured target path to its queued
     * location count. Targets with offlineQueue disabled report 0.
     * @throws TrackingException with NOT_CONFIGURED or STATUS_ERROR
     */
    @Synchronized
    fun getQueuedLocationsByTarget(): String {
        if (trackingState == STATE_IDLE) {
            throw TrackingException("NOT_CONFIGURED", "Call configure() before getQueuedLocationsByTarget()")
        }
        try {
            val counts = syncEngineController?.getQueuedCounts() ?: emptyMap()
            val result = JSONObject()
            for ((path, count) in counts) {
                result.put(path, count)
            }
            return result.toString()
        } catch (e: Exception) {
            throw TrackingException("STATUS_ERROR", "Failed to get queued locations by target: ${e.message}")
        }
    }

    // --- Private Implementation ---

    /**
     * Arm the native status watcher on the resolved status path.
     * Explicit control.statusPath wins; otherwise derive from the first RTDB
     * target path (e.g. "delivery/123/current" -> "delivery/123/status").
     */
    private fun armStatusWatcher() {
        val path = controlStatusPath ?: deriveStatusPath() ?: return
        val watcher = statusWatcher ?: StatusWatcher(controlStopValue) {
            // Fires on a Firebase listener thread — stop() is @Synchronized
            stop(STOP_REASON_REMOTE_STATUS)
        }
        watcher.arm(path)
        statusWatcher = watcher
    }

    /**
     * Derive the status path from the first configured RTDB target path:
     * strip the last segment and append "/status".
     * Returns null when firebase service isn't RTDB or derivation fails.
     */
    private fun deriveStatusPath(): String? {
        if (firebaseService != "RTDB") return null
        val firstPath = syncEngineController?.getTargetPaths()?.firstOrNull() ?: return null
        val parent = firstPath.substringBeforeLast('/', "")
        return if (parent.isEmpty()) null else "$parent/status"
    }

    /**
     * Initialize all engines with the current configuration.
     *
     * @param targetsJson JSON array string of sync target configurations
     * @throws IllegalArgumentException if targetsJson fails to parse or contains invalid targets
     */
    private fun initializeEngines(targetsJson: String) {
        // Location Engine
        locationEngine = LocationEngine(appContext).apply {
            setLocationListener(object : LocationEngine.LocationUpdateListener {
                override fun onLocationReceived(location: Location) {
                    handleLocationUpdate(location)
                }
            })
        }

        // Queue Engine (provides per-target offline queue operations)
        queueEngine = QueueEngine(appContext)

        // Network Listener
        networkListener = NetworkListener(appContext).apply {
            setNetworkStateListener(object : NetworkStateListener {
                override fun onNetworkAvailable() {
                    handleNetworkRestored()
                }

                override fun onNetworkLost() {
                    // No action needed - offline queue will hold data for targets with offlineQueue enabled
                }
            })
        }

        // SyncEngineController — replaces FirebaseSyncEngine
        // Parses targets JSON and instantiates one TargetHandler per target.
        // Throws IllegalArgumentException if targets JSON is invalid (Requirement 9.4)
        syncEngineController = SyncEngineController(
            targetsJson = targetsJson,
            firebaseService = firebaseService!!,
            networkChecker = { networkListener?.isOnline() ?: true },
            offlineQueueProvider = queueEngine,
            eventListener = object : TargetEventListener {
                override fun onWriteError(targetPath: String, method: String, errorCode: String, message: String) {
                    emitError(errorCode, "Target '$targetPath' ($method): $message")
                }

                override fun onQueueOverflow(targetPath: String) {
                    emitError("QUEUE_OVERFLOW", "Offline queue overflow for target '$targetPath' — oldest data point evicted")
                }
            }
        )

        // Activity Recognition Handler
        activityRecognitionHandler = ActivityRecognitionHandler(appContext).apply {
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
     * Applies distance/time filter, emits event to JS, dispatches to all sync targets.
     *
     * Requirement 3.1: Dispatches to all configured sync targets in parallel via SyncEngineController.
     */
    private fun handleLocationUpdate(location: Location) {
        // Apply distance/time filter
        if (!shouldAcceptLocation(location)) {
            return
        }

        // Update last location and timestamp
        lastLocation = location
        lastUpdateTimestamp = System.currentTimeMillis()

        // Emit location event to JavaScript (no-op if RN is dead)
        emitLocationUpdate(location)

        // Dispatch to all configured sync targets via SyncEngineController
        val dataPoint = LocationDataPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = location.time,
            accuracy = location.accuracy,
            speed = if (location.hasSpeed()) location.speed else null,
            altitude = if (location.hasAltitude()) location.altitude else null,
            bearing = if (location.hasBearing()) location.bearing else null
        )
        syncEngineController?.dispatchLocation(dataPoint)
    }

    /**
     * Distance/Time Matrix filter.
     * Accepts location based on the configured optimization mode:
     * - 'interval': time since last update >= intervalMs
     * - 'distance': distance from last location >= distanceFilterMeters
     * - 'both': both conditions must be met (default)
     *
     * First location is always accepted.
     * Invalid locations (accuracy < 0 or coordinate 0,0) are rejected.
     */
    private fun shouldAcceptLocation(newLocation: Location): Boolean {
        // Reject invalid coordinates
        if (newLocation.latitude == 0.0 && newLocation.longitude == 0.0) {
            return false
        }
        if (newLocation.accuracy < 0) {
            return false
        }

        val last = lastLocation ?: return true // First location always accepted

        // Check time condition
        val timeDiff = System.currentTimeMillis() - lastUpdateTimestamp
        val timeMet = timeDiff >= intervalMs

        // Check distance condition
        val distance = last.distanceTo(newLocation)
        val distanceMet = distance >= distanceFilterMeters

        return when (optimizationMode) {
            "interval" -> timeMet
            "distance" -> distanceMet
            else -> timeMet && distanceMet
        }
    }

    /**
     * Emit location update event to the registered listener (RN module).
     * No-op when no listener is registered (service-only process).
     */
    private fun emitLocationUpdate(location: Location) {
        try {
            eventListener?.onLocationUpdate(location)
        } catch (e: Exception) {
            // Listener may be detached mid-callback, ignore
        }
    }

    /**
     * Emit error event to the registered listener (RN module).
     * No-op when no listener is registered (service-only process).
     */
    private fun emitError(code: String, message: String) {
        try {
            eventListener?.onError(code, message)
        } catch (e: Exception) {
            // Listener may be detached mid-callback, ignore
        }
    }

    /**
     * Handle network restored: flush offline queues for all targets with offlineQueue enabled.
     */
    private fun handleNetworkRestored() {
        if (trackingState == STATE_TRACKING || trackingState == STATE_MOTION_SLEEP) {
            syncEngineController?.flushOfflineQueues()
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

    /**
     * Start location engine, activity recognition, and network listener.
     */
    private fun startLocationTracking() {
        // Start location engine
        // When mode is 'interval', pass 0f distance filter so FusedLocationProvider
        // delivers updates purely on interval without distance gating
        val effectiveDistanceFilter = if (optimizationMode == "interval") 0f else distanceFilterMeters
        locationEngine?.startLocationUpdates(intervalMs, effectiveDistanceFilter)

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

        // Arm stale-location watchdog so OS-throttled delivery self-recovers
        armStaleWatchdog()
    }

    /**
     * Stop location engine, activity recognition, and network listener.
     */
    private fun stopLocationTracking() {
        disarmStaleWatchdog()

        // Stop location engine
        locationEngine?.stopLocationUpdates()

        // Stop activity recognition
        activityRecognitionHandler?.stopActivityRecognition()

        // Stop network listener
        networkListener?.stopListening()
    }

    /**
     * Arm the stale-location watchdog. Ticks every intervalMs on the main
     * looper; a tick only acts when no fix arrived within the stale threshold.
     */
    private fun armStaleWatchdog() {
        disarmStaleWatchdog()
        val handler = Handler(Looper.getMainLooper())
        staleHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                checkStaleLocation()
                staleHandler?.postDelayed(this, intervalMs)
            }
        }
        staleRunnable = runnable
        handler.postDelayed(runnable, intervalMs)
    }

    private fun disarmStaleWatchdog() {
        staleRunnable?.let { staleHandler?.removeCallbacks(it) }
        staleRunnable = null
        staleHandler = null
    }

    /**
     * If no fix arrived within the stale threshold while tracking is active,
     * re-request location updates. The OS may silently stop delivering fixes
     * (doze, vendor power management) without telling the app — re-requesting
     * is safe and cheap, and does NOT restart the foreground service so it is
     * allowed while the app is in the background.
     */
    @Synchronized
    private fun checkStaleLocation() {
        if (trackingState != STATE_TRACKING) return
        val last = lastUpdateTimestamp
        if (last == 0L) return

        val threshold = maxOf(intervalMs * STALE_THRESHOLD_MULTIPLIER, STALE_MIN_THRESHOLD_MS)
        val elapsed = System.currentTimeMillis() - last
        if (elapsed <= threshold) return

        // Re-arm at most once per threshold window so a dead provider doesn't
        // cause a stop/start loop every interval tick.
        if (System.currentTimeMillis() - lastStaleRecoveryAt < threshold) return
        lastStaleRecoveryAt = System.currentTimeMillis()
        staleRecoveries++

        emitError(
            ERROR_LOCATION_STALE_RECOVERY,
            "No location fix for ${elapsed}ms while tracking — re-requesting location updates"
        )
        restartLocationUpdates()
    }

    private fun restartLocationUpdates() {
        try {
            val effectiveDistanceFilter = if (optimizationMode == "interval") 0f else distanceFilterMeters
            locationEngine?.stopLocationUpdates()
            locationEngine?.startLocationUpdates(intervalMs, effectiveDistanceFilter)
        } catch (e: Exception) {
            // Non-fatal — next watchdog tick retries after the threshold window
        }
    }

    /**
     * Register a BroadcastReceiver that listens for GPS/location mode changes.
     * When GPS is disabled during an active tracking session, tracking is paused.
     * When GPS is re-enabled, tracking resumes automatically.
     */
    private fun registerGpsStatusListener() {
        if (gpsStatusReceiver != null) return

        gpsStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                checkGpsStatus()
            }
        }

        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                gpsStatusReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            appContext.registerReceiver(gpsStatusReceiver, filter)
        }
    }

    /**
     * Unregister the GPS status BroadcastReceiver.
     */
    private fun unregisterGpsStatusListener() {
        gpsStatusReceiver?.let { receiver ->
            try {
                appContext.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, ignore
            }
            gpsStatusReceiver = null
        }
    }

    /**
     * Check current GPS status and pause/resume tracking accordingly.
     */
    private fun checkGpsStatus() {
        val gpsEnabled = permissionHandler.isGpsEnabled(appContext)

        if (!gpsEnabled && (trackingState == STATE_TRACKING || trackingState == STATE_MOTION_SLEEP)) {
            pauseTrackingDueToGps()
        } else if (gpsEnabled && trackingState == STATE_PAUSED_GPS && wasTrackingBeforeGpsDisabled) {
            resumeTrackingAfterGps()
        }
    }

    /**
     * Pause tracking when GPS is disabled while tracking is active.
     * Keeps the foreground service running so tracking can resume automatically.
     */
    private fun pauseTrackingDueToGps() {
        wasTrackingBeforeGpsDisabled = true
        stopLocationTracking()
        trackingState = STATE_PAUSED_GPS
        emitError(
            ERROR_GPS_DISABLED,
            "GPS/Location services were disabled. Tracking paused and will resume automatically when GPS is enabled."
        )
    }

    /**
     * Resume tracking after GPS has been re-enabled.
     */
    private fun resumeTrackingAfterGps() {
        try {
            startLocationTracking()
            trackingState = STATE_TRACKING
            emitError(
                ERROR_GPS_ENABLED,
                "GPS/Location services are enabled. Tracking resumed."
            )
        } catch (e: SecurityException) {
            trackingState = STATE_CONFIGURED
            wasTrackingBeforeGpsDisabled = false
            emitError(
                ERROR_PERMISSION_REVOKED,
                "Location permission was revoked while tracking was paused. Please grant permission to resume."
            )
        } catch (e: Exception) {
            emitError("GPS_RESUME_FAILED", "Failed to resume tracking after GPS enabled: ${e.message}")
        }
    }
}
