package com.livetracking.optimizer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Android Activity Recognition Handler that wraps ActivityRecognitionClient.
 * Detects user activity (STILL, ON_FOOT, IN_VEHICLE) and tracks STILL duration
 * to support Motion Sleep Mode for battery optimization.
 *
 * Requirements: 8.1, 8.3
 */
class ActivityRecognitionHandler(private val context: Context) {

    companion object {
        private const val ACTION_ACTIVITY_RECOGNIZED =
            "com.livetracking.ACTION_ACTIVITY_RECOGNIZED"

        /** Default detection interval in milliseconds */
        private const val DETECTION_INTERVAL_MS = 10_000L

        /** Minimum confidence level to accept an activity detection */
        private const val MIN_CONFIDENCE = 50
    }

    /**
     * Activity types recognized by the handler.
     */
    enum class ActivityType {
        STILL,
        ON_FOOT,
        IN_VEHICLE,
        UNKNOWN
    }

    /**
     * Listener interface for receiving activity state changes.
     */
    interface ActivityStateListener {
        /**
         * Called when the detected activity type changes.
         *
         * @param activity The new detected activity type
         */
        fun onActivityChanged(activity: ActivityType)

        /**
         * Called when the STILL duration exceeds the configured threshold.
         *
         * @param durationMs The duration in milliseconds that the device has been still
         */
        fun onStillDurationExceeded(durationMs: Long)
    }

    private val activityRecognitionClient: ActivityRecognitionClient =
        ActivityRecognition.getClient(context)

    private var listener: ActivityStateListener? = null
    private var currentActivity: ActivityType = ActivityType.UNKNOWN
    private var stillStartTimeMs: Long = 0L
    private var isStill: Boolean = false
    private var isRunning: Boolean = false

    /** Threshold in milliseconds for STILL duration notification (default: 3 minutes) */
    var stillThresholdMs: Long = 180_000L

    private var stillDurationExceededNotified: Boolean = false

    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (ActivityRecognitionResult.hasResult(intent)) {
                val result = ActivityRecognitionResult.extractResult(intent) ?: return
                handleActivityResult(result)
            }
        }
    }

    /**
     * Set the listener that will receive activity state changes.
     *
     * @param listener The listener to receive activity callbacks
     */
    fun setActivityStateListener(listener: ActivityStateListener) {
        this.listener = listener
    }

    /**
     * Start activity recognition updates.
     * Registers a BroadcastReceiver and requests periodic activity detection
     * from the ActivityRecognitionClient.
     *
     * @throws SecurityException if ACTIVITY_RECOGNITION permission is not granted
     */
    @Throws(SecurityException::class)
    fun startActivityRecognition() {
        if (isRunning) return

        registerReceiver()

        val pendingIntent = createPendingIntent()
        activityRecognitionClient.requestActivityUpdates(
            DETECTION_INTERVAL_MS,
            pendingIntent
        )

        isRunning = true
    }

    /**
     * Stop activity recognition updates.
     * Unregisters the BroadcastReceiver and removes activity detection requests.
     */
    fun stopActivityRecognition() {
        if (!isRunning) return

        val pendingIntent = createPendingIntent()
        activityRecognitionClient.removeActivityUpdates(pendingIntent)

        unregisterReceiver()

        // Reset state
        isRunning = false
        isStill = false
        stillStartTimeMs = 0L
        stillDurationExceededNotified = false
        currentActivity = ActivityType.UNKNOWN
    }

    /**
     * Get the current detected activity type.
     *
     * @return The current activity type
     */
    fun getCurrentActivity(): ActivityType = currentActivity

    /**
     * Get the current STILL duration in milliseconds.
     * Returns 0 if the device is not currently still.
     *
     * @return Duration in milliseconds that the device has been still, or 0
     */
    fun getStillDurationMs(): Long {
        if (!isStill || stillStartTimeMs == 0L) return 0L
        return SystemClock.elapsedRealtime() - stillStartTimeMs
    }

    /**
     * Check if the device is currently in STILL state.
     *
     * @return true if the device is detected as still
     */
    fun isDeviceStill(): Boolean = isStill

    private fun handleActivityResult(result: ActivityRecognitionResult) {
        val mostProbableActivity = result.mostProbableActivity
        if (mostProbableActivity.confidence < MIN_CONFIDENCE) return

        val newActivity = mapToActivityType(mostProbableActivity.type)
        val previousActivity = currentActivity
        currentActivity = newActivity

        if (newActivity != previousActivity) {
            listener?.onActivityChanged(newActivity)
        }

        when (newActivity) {
            ActivityType.STILL -> handleStillState()
            else -> handleMovingState()
        }
    }

    private fun handleStillState() {
        if (!isStill) {
            // Transition to STILL - start tracking duration
            isStill = true
            stillStartTimeMs = SystemClock.elapsedRealtime()
            stillDurationExceededNotified = false
        } else {
            // Already still - check if threshold exceeded
            val duration = SystemClock.elapsedRealtime() - stillStartTimeMs
            if (duration >= stillThresholdMs && !stillDurationExceededNotified) {
                stillDurationExceededNotified = true
                listener?.onStillDurationExceeded(duration)
            }
        }
    }

    private fun handleMovingState() {
        if (isStill) {
            // Transition from STILL to moving
            isStill = false
            stillStartTimeMs = 0L
            stillDurationExceededNotified = false
        }
    }

    private fun mapToActivityType(detectedActivityType: Int): ActivityType {
        return when (detectedActivityType) {
            DetectedActivity.STILL -> ActivityType.STILL
            DetectedActivity.ON_FOOT,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING -> ActivityType.ON_FOOT
            DetectedActivity.IN_VEHICLE -> ActivityType.IN_VEHICLE
            DetectedActivity.ON_BICYCLE -> ActivityType.IN_VEHICLE
            else -> ActivityType.UNKNOWN
        }
    }

    private fun createPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_ACTIVITY_RECOGNIZED)
        intent.setPackage(context.packageName)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun registerReceiver() {
        val intentFilter = IntentFilter(ACTION_ACTIVITY_RECOGNIZED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(activityReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(activityReceiver, intentFilter)
        }
    }

    private fun unregisterReceiver() {
        try {
            context.unregisterReceiver(activityReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
        }
    }
}
