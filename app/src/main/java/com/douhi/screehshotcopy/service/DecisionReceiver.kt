package com.douhi.screehshotcopy.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.douhi.screehshotcopy.App
import com.douhi.screehshotcopy.data.PendingDeletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Handles the Keep / Delete now buttons on the prompt notification.
 *
 * A broadcast receiver rather than an activity on purpose: notification actions that launch an
 * activity indirectly are blocked as trampolines on Android 12+, and the decision must work with
 * the app closed and the screen locked.
 */
class DecisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_KEEP && action != ACTION_DELETE_NOW) return
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrEmpty()) return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        val app = context.applicationContext as? App ?: return

        // The work touches DataStore and the filesystem, so keep the broadcast alive across it.
        val pendingResult = goAsync()
        app.container.appScope.launch {
            try {
                when (action) {
                    ACTION_KEEP -> app.container.janitor.keep(path, notifId)
                    ACTION_DELETE_NOW -> app.container.janitor.deleteNow(path, notifId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to handle $action for $path", e)
            } finally {
                try {
                    pendingResult.finish()
                } catch (e: Exception) {
                    Log.w(TAG, "finish() failed", e)
                }
            }
        }
    }

    companion object {
        const val ACTION_KEEP = "com.douhi.screehshotcopy.action.KEEP"
        const val ACTION_DELETE_NOW = "com.douhi.screehshotcopy.action.DELETE_NOW"

        private const val EXTRA_PATH = "path"
        private const val EXTRA_NOTIF_ID = "notif_id"
        private const val TAG = "DecisionReceiver"

        /**
         * Extras are not part of PendingIntent equality, so each (action, entry) pair needs its own
         * request code or two prompts would end up sharing one intent.
         */
        fun pendingIntent(context: Context, action: String, entry: PendingDeletion): PendingIntent {
            val requestCode = entry.notifId * 2 + if (action == ACTION_KEEP) 0 else 1
            val intent = Intent(context, DecisionReceiver::class.java)
                .setAction(action)
                .setPackage(context.packageName)
                .putExtra(EXTRA_PATH, entry.path)
                .putExtra(EXTRA_NOTIF_ID, entry.notifId)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
