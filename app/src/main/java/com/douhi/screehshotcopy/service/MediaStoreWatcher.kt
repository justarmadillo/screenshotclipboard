package com.douhi.screehshotcopy.service

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Secondary detector that watches the media database instead of the filesystem.
 *
 * inotify (see [ScreenshotWatcher]) is fast but not dependable on every device: FUSE-backed
 * emulated storage on some OEM builds drops events, and a few system UIs write the screenshot
 * through MediaStore in a way that produces no usable inotify event at all. Running both and
 * de-duplicating in the service means a screenshot has to defeat two independent mechanisms to
 * be missed.
 *
 * Detection is by row id, never by timestamp: ids only move forward, so there is no clock skew or
 * timezone to get wrong.
 */
class MediaStoreWatcher(
    private val context: Context,
    private val folderProvider: () -> File?,
    private val onFile: (File) -> Unit,
    private val onScanRequested: () -> Unit,
) : ContentObserver(null) {

    @Volatile
    private var lastId: Long = -1L

    @Volatile
    private var registered = false

    /** Records the current high-water mark so pre-existing screenshots are never re-processed. */
    fun start() {
        lastId = queryMaxId()
        try {
            context.contentResolver.registerContentObserver(COLLECTION, true, this)
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not register media observer", e)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            context.contentResolver.unregisterContentObserver(this)
        } catch (e: Exception) {
            Log.w(TAG, "Could not unregister media observer", e)
        }
    }

    override fun onChange(selfChange: Boolean) = onScanRequested()

    override fun onChange(selfChange: Boolean, uri: Uri?) = onScanRequested()

    /**
     * Emits every image added since the last scan that lives directly in the watched folder.
     * Call from a background coroutine — this touches the media database.
     */
    @Synchronized
    fun scan() {
        val folder = folderProvider() ?: return
        val folderPath = folder.absolutePath
        try {
            val maxId = queryMaxId()
            if (maxId < lastId) {
                // Media database was rebuilt and ids restarted; re-baseline rather than replaying
                // the user's entire gallery.
                Log.i(TAG, "Media ids went backwards, re-baselining")
                lastId = maxId
                return
            }
            if (maxId == lastId) return

            val since = lastId
            var highest = lastId
            context.contentResolver.query(
                COLLECTION,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA),
                "${MediaStore.Images.Media._ID} > ?",
                arrayOf(since.toString()),
                "${MediaStore.Images.Media._ID} ASC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                var scanned = 0
                while (cursor.moveToNext() && scanned < MAX_ROWS_PER_SCAN) {
                    scanned++
                    val id = cursor.getLong(idColumn)
                    if (id > highest) highest = id
                    val data = if (cursor.isNull(dataColumn)) null else cursor.getString(dataColumn)
                    if (data.isNullOrEmpty()) continue
                    val file = File(data)
                    if (file.parent == folderPath) onFile(file)
                }
            }
            lastId = highest
        } catch (e: Exception) {
            Log.w(TAG, "Media scan failed", e)
        }
    }

    private fun queryMaxId(): Long = try {
        context.contentResolver.query(
            COLLECTION,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            "${MediaStore.Images.Media._ID} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    } catch (e: Exception) {
        Log.w(TAG, "Could not read media high-water mark", e)
        // Falling back to the previous mark keeps the watcher from replaying the whole gallery.
        if (lastId >= 0) lastId else 0L
    }

    private companion object {
        const val TAG = "MediaStoreWatcher"
        const val MAX_ROWS_PER_SCAN = 50
        val COLLECTION: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
}
