package com.livetracking.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.livetracking.service.TrackingForegroundService

/**
 * BroadcastReceiver that listens for BOOT_COMPLETED to auto-restart
 * location tracking after device reboot.
 *
 * Requirements: 2.3
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (TrackingStateStore.isTrackingActive(context)) {
                TrackingForegroundService.start(context)
            }
        }
    }
}

/**
 * Helper object for persisting tracking state across device reboots.
 * Uses SharedPreferences to store whether tracking was active before reboot.
 */
object TrackingStateStore {

    private const val PREFS_NAME = "live_tracking_prefs"
    private const val KEY_IS_TRACKING_ACTIVE = "is_tracking_active"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Save the current tracking active state.
     * Call with `true` when tracking starts, `false` when tracking stops.
     */
    fun saveTrackingActive(context: Context, isActive: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_TRACKING_ACTIVE, isActive).apply()
    }

    /**
     * Check if tracking was active before the last reboot/shutdown.
     */
    fun isTrackingActive(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_TRACKING_ACTIVE, false)
    }
}
