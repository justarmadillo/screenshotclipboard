package com.douhi.screehshotcopy.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.douhi.screehshotcopy.App
import com.douhi.screehshotcopy.AppContainer
import com.douhi.screehshotcopy.R
import com.douhi.screehshotcopy.clipboard.CopyResult
import com.douhi.screehshotcopy.data.AppSettings
import com.douhi.screehshotcopy.notify.Notifications
import com.douhi.screehshotcopy.util.StatusBus
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that watches the screenshot folder, puts every new screenshot on the
 * clipboard, and then asks the user whether to keep the file.
 *
 * Deleting is the default and the fallback for every failure path except one: if the file cannot
 * be registered for a decision, it is kept. Losing a screenshot the user wanted is unrecoverable;
 * keeping one they did not is not.
 */
class MonitorService : Service() {

    private lateinit var container: AppContainer

    /** IO, not Default: everything this service does is filesystem and DataStore work. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Unhandled service error", e)
            StatusBus.update { it.copy(error = e.message ?: "unexpected service error") }
        }
    )

    private val bootstrapped = AtomicBoolean(false)
    private val configLock = Mutex()

    @Volatile
    private var latestStartId = 0
    private var settingsJob: Job? = null
    private var sweepJob: Job? = null
    private var healthJob: Job? = null

    private var fileWatcher: ScreenshotWatcher? = null
    private var mediaWatcher: MediaStoreWatcher? = null

    /** Relative path currently registered, or null when nothing is being watched. */
    private var watchedRelative: String? = null

    @Volatile
    private var watchedDir: File? = null

    @Volatile
    private var monitoringEnabled = false

    @Volatile
    private var keepTimeoutMs = AppSettings.DEFAULT_TIMEOUT_MS

    /** Paths already handled, so the two independent detectors never double-process a file. */
    private val recentlyHandled = LinkedHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        container = (application as App).container
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId

        // Must happen on every single start command, not just the first: the system gives a
        // service started with startForegroundService a few seconds to post its notification, and
        // skipping it on a redelivery is an instant ANR-and-kill.
        if (!promoteToForeground(getString(R.string.notif_running))) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            scope.launch { shutdown(startId, deletePending = false) }
            return START_NOT_STICKY
        }

        StatusBus.update {
            it.copy(running = true, error = null, notificationsBlocked = !Notifications.areNotificationsAllowed(this))
        }

        if (bootstrapped.compareAndSet(false, true)) {
            observeSettings()
            observePending()
            startHealthLoop()
            scope.launch {
                container.clipboardHelper.pruneCache()
                restorePrompts()
            }
        }

        // Re-read settings on every start command, not only the first. A start that arrives while
        // a shutdown is in flight would otherwise leave the service alive but with its watchers
        // already torn down, and the settings flow would have no change left to report.
        scope.launch { applySettings(container.settingsRepository.current()) }
        return START_STICKY
    }

    // region lifecycle helpers

    private fun promoteToForeground(text: String): Boolean = try {
        val notification = Notifications.buildServiceNotification(this, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.SERVICE_ID, notification)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground refused", e)
        StatusBus.update { it.copy(running = false, error = getString(R.string.err_service_blocked)) }
        false
    }

    private fun updateServiceNotification(text: String) {
        Notifications.post(this, Notifications.SERVICE_ID, Notifications.buildServiceNotification(this, text))
    }

    /**
     * Stops the service, but only if [stopId] is still the most recent start command.
     *
     * [stopId] must be the id that was current when the stop was *requested*, not when it is
     * carried out. Passing the latest id would make stopSelfResult always succeed, which is the
     * bug this guards against: toggling monitoring off and straight back on would stop the
     * freshly restarted service and leave it silently dead.
     */
    private suspend fun shutdown(stopId: Int, deletePending: Boolean) {
        teardownWatchers()
        monitoringEnabled = false
        drainPending(deletePending)
        if (stopSelfResult(stopId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            // A newer start command landed mid-shutdown; that start's own resync re-arms us.
            Log.i(TAG, "Shutdown skipped: a newer start command is pending")
        }
    }

    private suspend fun drainPending(delete: Boolean) {
        if (delete) {
            container.pendingRepository.takeAll().forEach { container.janitor.finalize(it) }
        } else {
            container.janitor.cancelAllPending()
        }
    }

    /** Re-posts prompts that outlived the process (or a reboot) so decisions are never orphaned. */
    private suspend fun restorePrompts() {
        val now = System.currentTimeMillis()
        container.pendingRepository.peek()
            .filter { it.deadlineMs > now }
            .forEach { entry ->
                val file = File(entry.path)
                if (file.exists()) {
                    Notifications.post(
                        this,
                        entry.notifId,
                        Notifications.buildPromptNotification(this, entry, file.name),
                    )
                } else {
                    // Deleted behind our back; drop the entry so it cannot fire against a path
                    // that some other app may reuse.
                    container.pendingRepository.remove(entry.path)
                    Notifications.cancel(this, entry.notifId)
                }
            }
    }

    // endregion

    // region settings

    private fun observeSettings() {
        settingsJob = scope.launch {
            while (isActive) {
                try {
                    container.settingsRepository.settings.collectLatest { settings ->
                        applySettings(settings)
                    }
                    // The settings flow never completes on its own; if it does, re-subscribe.
                    Log.w(TAG, "Settings flow completed unexpectedly, resubscribing")
                    delay(RETRY_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Settings collection failed, retrying", e)
                    delay(RETRY_MS)
                }
            }
        }
    }

    /**
     * Serialised: the settings flow and every start command can both land here, and two concurrent
     * calls to [registerWatchers] would leak an inotify watch and a ContentObserver.
     */
    private suspend fun applySettings(settings: AppSettings) = configLock.withLock {
        keepTimeoutMs = settings.keepTimeoutMs
        if (!settings.enabled) {
            shutdown(latestStartId, deletePending = false)
            return@withLock
        }
        monitoringEnabled = true
        if (settings.folderPath != watchedRelative || fileWatcher == null) {
            watchedRelative = settings.folderPath
            registerWatchers(settings.folderPath)
        }
    }

    // endregion

    // region watchers

    private fun registerWatchers(relativePath: String) {
        teardownWatchers()
        val dir = try {
            File(Environment.getExternalStorageDirectory(), relativePath)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot resolve external storage", e)
            null
        }

        if (dir == null || !dir.isDirectory) {
            watchedDir = null
            StatusBus.update {
                it.copy(
                    folder = dir?.absolutePath ?: relativePath,
                    error = getString(R.string.err_folder_missing, relativePath),
                )
            }
            // The health loop retries; no dedicated retry job to leak or double-schedule.
            return
        }

        watchedDir = dir
        StatusBus.update { it.copy(folder = dir.absolutePath, error = null) }

        fileWatcher = try {
            ScreenshotWatcher(
                folder = dir,
                onFile = { file -> onCandidate(file) },
                onFolderGone = { scope.launch { reRegisterCurrent() } },
            ).also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "FileObserver registration failed for $dir", e)
            StatusBus.update { it.copy(error = getString(R.string.err_watch_failed, dir.absolutePath)) }
            null
        }

        try {
            // Assign before starting: the observer can fire the moment it is registered, and the
            // scan callback reads this field.
            val watcher = MediaStoreWatcher(
                context = this,
                folderProvider = { watchedDir },
                onFile = { file -> onCandidate(file) },
                onScanRequested = { scope.launch { mediaWatcher?.scan() } },
            )
            mediaWatcher = watcher
            watcher.start()
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore observer registration failed", e)
            mediaWatcher = null
        }
    }

    /**
     * Re-arms the watchers on the folder already being watched. Takes [configLock] because it is
     * reached from the inotify thread and from the health loop, either of which could otherwise
     * interleave with a settings change and leak a watch.
     */
    private suspend fun reRegisterCurrent() = configLock.withLock {
        val relative = watchedRelative
        if (relative != null && monitoringEnabled) registerWatchers(relative)
    }

    private fun teardownWatchers() {
        fileWatcher?.stop()
        fileWatcher = null
        mediaWatcher?.stop()
        mediaWatcher = null
        watchedDir = null
    }

    /**
     * Cheap liveness check. inotify watches die silently when the folder is replaced and
     * ContentObservers can be dropped, so the service verifies rather than assumes. Every fourth
     * tick it also re-scans the media database, which catches anything both detectors missed.
     */
    private fun startHealthLoop() {
        healthJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(HEALTH_INTERVAL_MS)
                if (!monitoringEnabled) continue
                try {
                    val dir = watchedDir
                    val healthy = fileWatcher != null && dir != null && dir.isDirectory
                    if (!healthy) {
                        Log.i(TAG, "Watcher unhealthy, re-registering on $watchedRelative")
                        reRegisterCurrent()
                    } else if (++tick % MEDIA_SCAN_EVERY == 0) {
                        mediaWatcher?.scan()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Health check failed", e)
                }
            }
        }
    }

    // endregion

    // region screenshot handling

    private fun onCandidate(file: File) {
        if (!monitoringEnabled) return
        val dir = watchedDir ?: return
        // Both detectors can report files outside the watched folder; ignore them rather than
        // trusting the caller.
        if (!isDirectChildOf(file, dir)) return
        if (!claim(file.absolutePath)) return
        scope.launch { process(file) }
    }

    /**
     * Paths reach here from two sources that spell the same folder differently (`/sdcard/...` vs
     * `/storage/emulated/0/...`), so compare resolved paths, not strings.
     */
    private fun isDirectChildOf(file: File, dir: File): Boolean {
        val parent = file.parentFile ?: return false
        if (parent.absolutePath == dir.absolutePath) return true
        return try {
            parent.canonicalPath == dir.canonicalPath
        } catch (e: IOException) {
            false
        }
    }

    private suspend fun process(file: File) {
        val result = try {
            container.clipboardHelper.copyToClipboard(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Copy threw for ${file.name}", e)
            CopyResult(false, "exception: ${e.message}")
        }

        if (!result.ok) {
            release(file.absolutePath)
            val message = getString(R.string.err_copy_failed, file.name) + " — " + result.source
            StatusBus.update { it.copy(error = message) }
            updateServiceNotification(message)
            return
        }

        StatusBus.update { it.copy(lastAction = getString(R.string.copied, file.name), error = null) }

        val entry = container.pendingRepository.add(file.absolutePath, keepTimeoutMs)
        if (entry == null) {
            // The decision could not be recorded, so it could never be honoured either. Keep the
            // file rather than delete something the user was never given the chance to save.
            val message = getString(R.string.copied_kept_unsafe, file.name)
            Log.w(TAG, "Could not queue ${file.name} for deletion; keeping it")
            StatusBus.update { it.copy(lastAction = message) }
            updateServiceNotification(message)
            return
        }

        val posted = Notifications.post(
            this,
            entry.notifId,
            Notifications.buildPromptNotification(this, entry, file.name),
        )
        StatusBus.update { it.copy(notificationsBlocked = !posted) }
        updateServiceNotification(getString(R.string.copied, file.name))
    }

    /** Returns true if this call is the first to take ownership of [path]. */
    @Synchronized
    private fun claim(path: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val iterator = recentlyHandled.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > DEDUP_TTL_MS) iterator.remove() else break
        }
        while (recentlyHandled.size >= MAX_RECENT) {
            val oldest = recentlyHandled.keys.firstOrNull() ?: break
            recentlyHandled.remove(oldest)
        }
        return recentlyHandled.put(path, now) == null
    }

    @Synchronized
    private fun release(path: String) {
        recentlyHandled.remove(path)
    }

    // endregion

    // region deadlines

    /**
     * Deletes screenshots whose deadline has passed.
     *
     * The queue is the source of truth and lives in DataStore, so this survives the process being
     * killed: on the next start the first emission already contains the overdue entries.
     * [collectLatest] restarts the wait whenever the queue changes, which is what makes a Keep tap
     * take effect immediately instead of at the next poll.
     */
    private fun observePending() {
        sweepJob = scope.launch {
            container.pendingRepository.pending.collectLatest { snapshot ->
                var current = snapshot
                // currentCoroutineContext(), not the enclosing scope's isActive: collectLatest
                // cancels *this* block on every queue change, and that is the signal to stop.
                while (currentCoroutineContext().isActive) {
                    StatusBus.update { it.copy(pendingCount = current.size) }
                    if (current.isEmpty()) return@collectLatest
                    val wait = current.minOf { it.deadlineMs } - System.currentTimeMillis()
                    if (wait > 0) {
                        delay(wait.coerceAtMost(MAX_SLEEP_MS))
                        current = container.pendingRepository.peek()
                        continue
                    }
                    val due = container.pendingRepository.takeDue(
                        System.currentTimeMillis(),
                        AppSettings.MAX_TIMEOUT_MS,
                    )
                    due.forEach { container.janitor.finalize(it) }
                    // Nothing was claimed: another component got there first. Back off so this can
                    // never become a hot loop.
                    if (due.isEmpty()) delay(IDLE_TICK_MS)
                    current = container.pendingRepository.peek()
                }
            }
        }
    }

    // endregion

    override fun onDestroy() {
        teardownWatchers()
        settingsJob?.cancel()
        sweepJob?.cancel()
        healthJob?.cancel()
        scope.cancel()
        StatusBus.update { it.copy(running = false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.douhi.screehshotcopy.action.STOP_MONITOR"

        private const val TAG = "MonitorService"
        private const val RETRY_MS = 5_000L
        private const val HEALTH_INTERVAL_MS = 15_000L
        private const val MEDIA_SCAN_EVERY = 4
        private const val MAX_SLEEP_MS = 30_000L
        private const val IDLE_TICK_MS = 1_000L
        private const val DEDUP_TTL_MS = 5 * 60_000L
        private const val MAX_RECENT = 512
    }
}
