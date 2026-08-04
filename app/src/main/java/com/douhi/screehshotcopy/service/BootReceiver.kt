package com.douhi.screehshotcopy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.douhi.screehshotcopy.App
import kotlinx.coroutines.launch

/**
 * Restarts monitoring after a reboot or an app update.
 *
 * `specialUse` is one of the foreground service types Android 15+ still allows to be started from
 * BOOT_COMPLETED, so this keeps working on current and future releases.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val app = context.applicationContext as? App ?: return
        val pendingResult = goAsync()
        app.container.appScope.launch {
            try {
                if (app.container.settingsRepository.current().enabled) {
                    ContextCompat.startForegroundService(app, Intent(app, MonitorService::class.java))
                }
            } catch (e: Exception) {
                // Battery-restricted or OEM-blocked starts throw here; the user restarts from the
                // app, which is what the on-screen hint tells them to do.
                Log.w(TAG, "Failed to restart monitor after ${intent.action}", e)
            } finally {
                try {
                    pendingResult.finish()
                } catch (e: Exception) {
                    Log.w(TAG, "finish() failed", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
