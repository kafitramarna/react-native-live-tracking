package com.livetracking.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.livetracking.receiver.TrackingStateStore
import com.livetracking.runtime.TrackingRuntime
import org.json.JSONObject

/**
 * Android Foreground Service for persistent location tracking.
 * Keeps the app alive in the background with a persistent notification,
 * preventing the OS from killing the process.
 *
 * Requirements: 2.1, 2.4, 2.5
 */
class TrackingForegroundService : Service() {

    companion object {
        const val DEFAULT_CHANNEL_ID = "live_tracking_channel"
        const val DEFAULT_CHANNEL_NAME = "Live Tracking"
        const val NOTIFICATION_ID = 1001

        private const val EXTRA_NOTIFICATION_TITLE = "extra_notification_title"
        private const val EXTRA_NOTIFICATION_TEXT = "extra_notification_text"
        private const val EXTRA_NOTIFICATION_ICON = "extra_notification_icon"
        private const val EXTRA_CHANNEL_ID = "extra_channel_id"
        private const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        private const val EXTRA_START_REASON = "extra_start_reason"
        private const val EXTRA_WAKE_LOCK = "extra_wake_lock"
        private const val WAKE_LOCK_TAG = "LiveTracking::TrackingWakeLock"

        /**
         * Start the foreground service with notification configuration.
         *
         * @param reason why the service is being started (e.g. "boot") —
         *        surfaced to TrackingRuntime for self-heal and observability
         */
        fun start(
            context: Context,
            title: String = "Live Tracking",
            text: String = "Tracking your location",
            icon: String? = null,
            channelId: String = DEFAULT_CHANNEL_ID,
            channelName: String = DEFAULT_CHANNEL_NAME,
            reason: String? = null,
            wakeLock: Boolean = true
        ) {
            val intent = Intent(context, TrackingForegroundService::class.java).apply {
                putExtra(EXTRA_NOTIFICATION_TITLE, title)
                putExtra(EXTRA_NOTIFICATION_TEXT, text)
                putExtra(EXTRA_CHANNEL_ID, channelId)
                putExtra(EXTRA_CHANNEL_NAME, channelName)
                putExtra(EXTRA_WAKE_LOCK, wakeLock)
                if (icon != null) {
                    putExtra(EXTRA_NOTIFICATION_ICON, icon)
                }
                if (reason != null) {
                    putExtra(EXTRA_START_REASON, reason)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the foreground service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, TrackingForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? {
        // Not a bound service
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_NOTIFICATION_TITLE) ?: "Live Tracking"
        val text = intent?.getStringExtra(EXTRA_NOTIFICATION_TEXT) ?: "Tracking your location"
        val iconName = intent?.getStringExtra(EXTRA_NOTIFICATION_ICON)
        val channelId = intent?.getStringExtra(EXTRA_CHANNEL_ID) ?: DEFAULT_CHANNEL_ID
        val channelName = intent?.getStringExtra(EXTRA_CHANNEL_NAME) ?: DEFAULT_CHANNEL_NAME

        createNotificationChannel(channelId, channelName)

        val notification = buildNotification(title, text, iconName, channelId)
        startForeground(NOTIFICATION_ID, notification)

        if (shouldHoldWakeLock(intent)) {
            acquireWakeLock()
        }

        // Self-heal hook: rebuild engines from persisted config and re-arm the
        // native status watcher after process death, sticky restart, or reboot.
        val reason = intent?.getStringExtra(EXTRA_START_REASON)
            ?: if (intent == null) "sticky_restart" else "explicit"
        val result = TrackingRuntime.getInstance(applicationContext).onServiceStarted(reason)
        if (result == TrackingRuntime.ServiceStartResult.RESUME_FAILED) {
            // Nothing valid to resume — don't leave a zombie notification running
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        TrackingRuntime.getInstance(applicationContext).onServiceDestroyed()
    }

    /**
     * Whether the service should hold a partial wake lock while tracking.
     * Reads the explicit intent flag first; on sticky restart (null intent)
     * falls back to the persisted config so a self-healed session keeps the
     * same policy the JS layer configured.
     */
    private fun shouldHoldWakeLock(intent: Intent?): Boolean {
        if (intent != null && intent.hasExtra(EXTRA_WAKE_LOCK)) {
            return intent.getBooleanExtra(EXTRA_WAKE_LOCK, true)
        }
        return try {
            val config = TrackingStateStore.loadConfig(applicationContext)
            config
                ?.let { JSONObject(it).optJSONObject("android")?.optBoolean("wakeLock", true) }
                ?: true
        } catch (e: Exception) {
            true
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    /**
     * Creates the notification channel for Android O and above.
     */
    private fun createNotificationChannel(channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for live location tracking"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the persistent notification displayed while tracking is active.
     */
    private fun buildNotification(
        title: String,
        text: String,
        iconName: String?,
        channelId: String
    ): Notification {
        val iconResId = if (iconName != null) {
            resources.getIdentifier(iconName, "drawable", packageName)
        } else {
            0
        }

        // Fallback to app icon if custom icon not found
        val finalIconResId = if (iconResId != 0) {
            iconResId
        } else {
            applicationInfo.icon
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(finalIconResId)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
