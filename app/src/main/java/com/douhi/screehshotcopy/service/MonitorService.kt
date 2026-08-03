package com.douhi.screehshotcopy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.douhi.screehshotcopy.App
import com.douhi.screehshotcopy.AppContainer
import com.douhi.screehshotcopy.MainActivity
import com.douhi.screehshotcopy.R
import com.douhi.screehshotcopy.clipboard.CopyResult
import com.douhi.screehshotcopy.data.AppSettings
import com.douhi.screehshotcopy.data.DeleteBehavior
import com.douhi.screehshotcopy.util.StatusBus
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MonitorService : Service() {

    private lateinit var container: AppContainer
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Unhandled service error", e)
            StatusBus.update { it.copy(error = e.message ?: "unexpected service error") }
        }
    )
    private var settingsJob: Job? = null
    private var retryJob: Job? = null
    private var watcher: ScreenshotWatcher? = null
    private var watchedFolder: String = ""
    private var deleteBehavior: DeleteBehavior = DeleteBehavior.DELETE
    private var deleteDelayMs: Long = AppSettings.DEFAULT_DELAY_MS

    override fun onCreate() {
        super.onCreate()
        container = (application as App).container
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (settingsJob == null) {
            val notification = buildNotification(getString(R.string.notif_running))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            StatusBus.update { it.copy(running = true, error = null) }
            observeSettings()
        }
        return START_STICKY
    }

    private fun observeSettings() {
        settingsJob = scope.launch {
            try {
                container.settingsRepository.settings.collect { settings ->
                    if (!settings.enabled) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    deleteBehavior = settings.deleteBehavior
                    deleteDelayMs = settings.deleteDelayMs
                    if (settings.folderPath != watchedFolder) {
                        watchedFolder = settings.folderPath
                        reRegisterWatcher()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Settings collection failed, retrying", e)
                delay(5_000)
                observeSettings()
            }
        }
    }

    private fun reRegisterWatcher() {
        watcher?.stopWatching()
        watcher = null
        retryJob?.cancel()
        val dir = File(Environment.getExternalStorageDirectory(), watchedFolder)
        if (!dir.isDirectory) {
            StatusBus.update {
                it.copy(
                    folder = dir.absolutePath,
                    error = getString(R.string.err_folder_missing, watchedFolder),
                )
            }
            retryJob = scope.launch {
                delay(FOLDER_RETRY_MS)
                reRegisterWatcher()
            }
            return
        }
        StatusBus.update { it.copy(folder = dir.absolutePath, error = null) }
        try {
            watcher = ScreenshotWatcher(dir) { file -> onScreenshot(file) }
                .also { it.startWatching() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FileObserver on $dir", e)
            StatusBus.update { it.copy(error = getString(R.string.err_watch_failed, dir.absolutePath)) }
            retryJob = scope.launch {
                delay(FOLDER_RETRY_MS)
                reRegisterWatcher()
            }
        }
    }

    private fun onScreenshot(file: File) {
        scope.launch {
            val result = try {
                container.clipboardHelper.copyToClipboard(file)
            } catch (e: Exception) {
                CopyResult(false, "exception: ${e.message}")
            }
            if (result.ok) {
                val copied = getString(R.string.copied, file.name) +
                    " (via ${result.source}, ${result.mime}, clip=[${result.clipMimes}])"
                updateNotification(copied)
                StatusBus.update { it.copy(lastAction = copied, error = null) }
                if (deleteBehavior == DeleteBehavior.DELETE) {
                    delay(deleteDelayMs)
                    val deleted = container.deletionHelper.delete(file)
                    val message =
                        if (deleted) getString(R.string.deleted, file.name)
                        else getString(R.string.delete_failed, file.name)
                    updateNotification(message)
                    StatusBus.update { it.copy(lastAction = message) }
                }
            } else {
                val message = getString(R.string.err_copy_failed, file.name) + " — " + result.source
                StatusBus.update { it.copy(error = message) }
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        watcher?.stopWatching()
        watcher = null
        settingsJob?.cancel()
        retryJob?.cancel()
        scope.cancel()
        StatusBus.update { it.copy(running = false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "monitor"
        const val FOLDER_RETRY_MS = 15_000L
        const val TAG = "MonitorService"
    }
}
