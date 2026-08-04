package com.douhi.screehshotcopy.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.douhi.screehshotcopy.MainActivity
import com.douhi.screehshotcopy.R
import com.douhi.screehshotcopy.data.PendingDeletion
import com.douhi.screehshotcopy.service.DecisionReceiver

/**
 * Every notification the app posts. Centralised so ids, channels and the "posting must never
 * crash the caller" rule are enforced in exactly one place.
 */
object Notifications {

    const val SERVICE_ID = 1

    /**
     * Every short-lived result (copied / kept / deleted / failed) shares one id, so they replace
     * each other instead of stacking up in the shade. Safe against the prompt ids, which start at
     * [com.douhi.screehshotcopy.data.PendingRepository] NOTIF_ID_BASE (1000).
     */
    const val TRANSIENT_ID = 2

    private const val TAG = "Notifications"
    private const val CHANNEL_SERVICE = "monitor"

    /** New id: channel importance/sound cannot be changed after creation, so v1 users get a fresh one. */
    private const val CHANNEL_PROMPT = "keep_prompt"

    private const val CONFIRM_TIMEOUT_MS = 5_000L

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    context.getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROMPT,
                    context.getString(R.string.notif_channel_prompt),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notif_channel_prompt_desc)
                    // Heads-up so the prompt is actually seen, but silent: a screenshot is
                    // already a deliberate user action, it does not need a chime.
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Channel creation failed", e)
        }
    }

    /**
     * The ongoing foreground notification. Its text is deliberately fixed: it states what the
     * service *is*, not what it last did. Per-screenshot messages are transient
     * ([buildResultNotification]) so nothing stale is ever left sitting in the shade.
     */
    fun buildServiceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_running))
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    /**
     * The keep-or-lose prompt. Not ongoing on purpose: swiping it away is not "keep", it just
     * dismisses the prompt and the file is still deleted at the deadline. That keeps the app's
     * promise — the default is always delete.
     */
    fun buildPromptNotification(
        context: Context,
        entry: PendingDeletion,
        fileName: String,
    ): Notification {
        val remaining = (entry.deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
        return NotificationCompat.Builder(context, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(context.getString(R.string.prompt_title))
            .setContentText(context.getString(R.string.prompt_text, fileName))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.prompt_text_long, fileName))
            )
            .setContentIntent(openAppIntent(context))
            .addAction(
                0,
                context.getString(R.string.action_keep),
                DecisionReceiver.pendingIntent(context, DecisionReceiver.ACTION_KEEP, entry),
            )
            .addAction(
                0,
                context.getString(R.string.action_delete_now),
                DecisionReceiver.pendingIntent(context, DecisionReceiver.ACTION_DELETE_NOW, entry),
            )
            // Live countdown to the deadline so the user knows how long they have.
            .setWhen(entry.deadlineMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            // Safety net: if the app is killed before it can cancel this, the system clears it.
            .setTimeoutAfter(remaining + CONFIRM_TIMEOUT_MS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_STATUS)
            // Private, not secret: the countdown is running whether the screen is locked or not,
            // so the Keep button has to be reachable from the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    /**
     * Short-lived confirmation of what just happened to a screenshot. Posted on the low-importance
     * channel and always with a timeout: this is feedback, not an interruption, and it must clear
     * itself even if the app is killed before it can cancel it. Always posted with [TRANSIENT_ID].
     */
    fun buildResultNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openAppIntent(context))
            .setTimeoutAfter(CONFIRM_TIMEOUT_MS)
            .setAutoCancel(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    /** Posts without ever throwing: a notification failure must not abort a delete or a copy. */
    @SuppressLint("MissingPermission") // areNotificationsAllowed() checks POST_NOTIFICATIONS.
    fun post(context: Context, id: Int, notification: Notification): Boolean = try {
        if (areNotificationsAllowed(context)) {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        Log.w(TAG, "notify($id) failed", e)
        false
    }

    fun cancel(context: Context, id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(id)
        } catch (e: Exception) {
            Log.w(TAG, "cancel($id) failed", e)
        }
    }

    fun areNotificationsAllowed(context: Context): Boolean = try {
        val runtimeGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        // Both matter: the runtime grant can be present while the user has still switched the
        // app's notifications off in system settings.
        runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (e: Exception) {
        Log.w(TAG, "Notification permission check failed", e)
        false
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
