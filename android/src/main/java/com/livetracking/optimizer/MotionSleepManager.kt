package com.livetracking.optimizer

import android.os.SystemClock
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Priority
import com.livetracking.location.LocationEngine

/**
 * Manages Motion Sleep Mode for battery optimization.
 *
 * When the device is detected as STILL for more than 3 minutes and `stopWhenStill` is enabled,
 * this manager switches location updates to low-power mode. When movement is detected again,
 * it restores high-accuracy location updates.
 *
 * Requirements: 8.1, 8.2, 8.4
 */
class MotionSleepManager(
    private val locationEngine: LocationEngine,
    private val stopWhenStill: Boolean,
    private val intervalMs: Long,
    private val distanceFilter: Float
) {

    /**
     * Listener interface for motion sleep mode state changes.
     */
    interface MotionSleepListener {
        fun onSleepModeActivated()
        fun onSleepModeDeactivated()
    }

    companion object {
        /**
         * Duration threshold in milliseconds before entering sleep mode.
         * Device must be STILL for more than 3 minutes (180,000 ms).
         */
        const val STILL_THRESHOLD_MS = 180_000L
    }

    private var listener: MotionSleepListener? = null
    private var inSleepMode: Boolean = false
    private var stillStartTime: Long = 0L
    private var isStill: Boolean = false

    /**
     * Set the listener that will receive sleep mode state change callbacks.
     *
     * @param listener The listener to receive callbacks, or null to remove
     */
    fun setListener(listener: MotionSleepListener?) {
        this.listener = listener
    }

    /**
     * Called when an activity detection update is received.
     *
     * If `stopWhenStill` is false, this method is a no-op.
     *
     * Behavior:
     * - STILL detected: starts tracking still duration. If still > 3 minutes, enters sleep mode.
     * - ON_FOOT or IN_VEHICLE detected: exits sleep mode if active, resets still tracking.
     *
     * @param activityType The detected activity type from ActivityRecognitionClient
     *                     (e.g., DetectedActivity.STILL, DetectedActivity.ON_FOOT)
     */
    fun onActivityDetected(activityType: Int) {
        if (!stopWhenStill) {
            return
        }

        when (activityType) {
            DetectedActivity.STILL -> {
                if (!isStill) {
                    // Start tracking still duration
                    isStill = true
                    stillStartTime = SystemClock.elapsedRealtime()
                } else {
                    // Already still, check if threshold exceeded
                    val stillDuration = SystemClock.elapsedRealtime() - stillStartTime
                    if (stillDuration > STILL_THRESHOLD_MS && !inSleepMode) {
                        enterSleepMode()
                    }
                }
            }

            DetectedActivity.ON_FOOT,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE -> {
                isStill = false
                stillStartTime = 0L
                if (inSleepMode) {
                    exitSleepMode()
                }
            }
        }
    }

    /**
     * Returns whether the manager is currently in sleep mode (low-power location).
     *
     * @return true if sleep mode is active, false otherwise
     */
    fun isInSleepMode(): Boolean {
        return inSleepMode
    }

    /**
     * Enter sleep mode: stop current location updates and restart with low-power priority.
     * This reduces GPS usage when the device is stationary.
     */
    private fun enterSleepMode() {
        inSleepMode = true
        locationEngine.stopLocationUpdates()
        locationEngine.startLocationUpdates(intervalMs * 5, distanceFilter, Priority.PRIORITY_LOW_POWER)
        listener?.onSleepModeActivated()
    }

    /**
     * Exit sleep mode: stop current location updates and restart with high-accuracy priority.
     * This restores full GPS accuracy when movement is detected.
     */
    private fun exitSleepMode() {
        inSleepMode = false
        locationEngine.stopLocationUpdates()
        locationEngine.startLocationUpdates(intervalMs, distanceFilter, Priority.PRIORITY_HIGH_ACCURACY)
        listener?.onSleepModeDeactivated()
    }
}
