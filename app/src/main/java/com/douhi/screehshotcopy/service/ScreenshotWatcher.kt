package com.douhi.screehshotcopy.service

import android.os.Build
import android.os.FileObserver
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File

/**
 * inotify-based detector for new files in the screenshot folder.
 *
 * Primary detector: it fires the instant the screenshot is written. It is deliberately paired with
 * [MediaStoreWatcher] in the service, because inotify is not reliable on every OEM's FUSE-backed
 * emulated storage — either detector alone would miss screenshots on some devices.
 */
class ScreenshotWatcher(
    private val folder: File,
    private val onFile: (File) -> Unit,
    private val onFolderGone: () -> Unit,
) {

    private val observer: FileObserver = createObserver(folder, MASK, ::handleEvent)

    fun start() {
        observer.startWatching()
    }

    fun stop() {
        try {
            observer.stopWatching()
        } catch (e: Exception) {
            Log.w(TAG, "stopWatching failed", e)
        }
    }

    private fun handleEvent(event: Int, path: String?) {
        try {
            val masked = event and FileObserver.ALL_EVENTS
            if (masked and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0) {
                // The screenshot folder itself was replaced. The inotify watch is now attached to a
                // dead inode and will never fire again, so the service has to re-register.
                Log.i(TAG, "Watched folder disappeared: $folder")
                onFolderGone()
                return
            }
            if (masked and (FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) == 0) return
            if (path.isNullOrEmpty()) return
            val name = path.substringAfterLast('/')
            if (name.isEmpty() || name.startsWith('.')) return
            if (!isImage(name)) return
            onFile(File(folder, name))
        } catch (e: Exception) {
            // An exception thrown back into the inotify thread kills the observer for good.
            Log.w(TAG, "Event handling failed for $path", e)
        }
    }

    private fun isImage(name: String): Boolean =
        IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    private companion object {
        const val TAG = "ScreenshotWatcher"

        val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp", ".heic", ".heif", ".bmp", ".gif")

        const val MASK = FileObserver.CLOSE_WRITE or
            FileObserver.MOVED_TO or
            FileObserver.DELETE_SELF or
            FileObserver.MOVE_SELF

        /**
         * The String constructor is deprecated from API 29 and the File one does not exist before
         * it, so pick per platform instead of carrying a deprecation into a build that will never
         * be updated again.
         */
        fun createObserver(folder: File, mask: Int, onEvent: (Int, String?) -> Unit): FileObserver =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ModernObserver(folder, mask, onEvent)
            } else {
                LegacyObserver(folder.absolutePath, mask, onEvent)
            }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private class ModernObserver(
        folder: File,
        mask: Int,
        private val callback: (Int, String?) -> Unit,
    ) : FileObserver(folder, mask) {
        override fun onEvent(event: Int, path: String?) = callback(event, path)
    }

    @Suppress("DEPRECATION")
    private class LegacyObserver(
        path: String,
        mask: Int,
        private val callback: (Int, String?) -> Unit,
    ) : FileObserver(path, mask) {
        override fun onEvent(event: Int, path: String?) = callback(event, path)
    }
}
