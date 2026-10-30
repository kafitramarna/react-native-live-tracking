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
 * Passes a "boot" reason extra so the service can report how it was started
 * and let TrackingRuntime self-heal (resume engines + re-arm status watcher).
 *
 * Requirements: 2.3
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (TrackingStateStore.isTrackingActive(context)) {
                TrackingForegroundService.start(context, reason = "boot")
            }
        }
    }
}

/**
 * Helper object for persisting tracking state across process death, service
 * restarts, and device reboots. Uses SharedPreferences.
 *
 * Stored keys:
 * - is_tracking_active: whether tracking was active when last known
 * - config_json: last successful configure() JSON, used to rebuild engines
 *   after process death without the JS layer
 * - last_stop_reason / last_restart_reason: one-shot observability markers
 *   consumed by the JS layer (via getStatus) for reporting
 */
object TrackingStateStore {

    private const val PREFS_NAME = "live_tracking_prefs"
    private const val KEY_IS_TRACKING_ACTIVE = "is_tracking_active"
    private const val KEY_CONFIG_JSON = "config_json"
    private const val KEY_LAST_STOP_REASON = "last_stop_reason"
    private const val KEY_LAST_RESTART_REASON = "last_restart_reason"

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

    /**
     * Persist the full configure() JSON so engines can be rebuilt natively
     * after process death or reboot without the RN/JS layer.
     */
    fun saveConfig(context: Context, configJson: String) {
        getPrefs(context).edit().putString(KEY_CONFIG_JSON, configJson).apply()
    }

    /**
     * Load the last saved config JSON, or null if never configured.
     */
    fun loadConfig(context: Context): String? {
        return getPrefs(context).getString(KEY_CONFIG_JSON, null)
    }

    /**
     * Record why tracking last stopped (e.g. "manual", "remote_status",
     * "permission_revoked"). One-shot marker for observability.
     */
    fun saveLastStopReason(context: Context, reason: String) {
        getPrefs(context).edit().putString(KEY_LAST_STOP_REASON, reason).apply()
    }

    /**
     * Read and clear the last stop reason. Returns null if none was recorded.
     */
    fun consumeLastStopReason(context: Context): String? {
        val reason = getPrefs(context).getString(KEY_LAST_STOP_REASON, null)
        if (reason != null) {
            getPrefs(context).edit().remove(KEY_LAST_STOP_REASON).apply()
        }
        return reason
    }

    /**
     * Record why the service was restarted (e.g. "sticky_restart", "boot",
     * "service_destroyed_while_tracking"). One-shot marker for observability.
     */
    fun saveLastRestartReason(context: Context, reason: String) {
        getPrefs(context).edit().putString(KEY_LAST_RESTART_REASON, reason).apply()
    }

    /**
     * Read and clear the last restart reason. Returns null if none was recorded.
     */
    fun consumeLastRestartReason(context: Context): String? {
        val reason = getPrefs(context).getString(KEY_LAST_RESTART_REASON, null)
        if (reason != null) {
            getPrefs(context).edit().remove(KEY_LAST_RESTART_REASON).apply()
        }
        return reason
    }
}
