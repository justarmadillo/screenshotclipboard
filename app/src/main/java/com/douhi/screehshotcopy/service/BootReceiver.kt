package com.douhi.screehshotcopy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.douhi.screehshotcopy.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as App
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = app.container.settingsRepository.settings.first()
                if (settings.enabled) {
                    ContextCompat.startForegroundService(app, Intent(app, MonitorService::class.java))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restart monitor after $action", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}