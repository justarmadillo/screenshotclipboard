package com.douhi.screehshotcopy.deletion

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File

class DeletionHelper(private val context: Context) {

    /**
     * Removes [file] from disk and from the media database.
     *
     * Returns true when the file is gone afterwards — including when it was already gone, because
     * "nothing left to delete" is the outcome the caller asked for. Every strategy is attempted in
     * turn and the result is verified against the filesystem rather than trusted.
     */
    fun delete(file: File): Boolean {
        if (!file.exists()) {
            // Already gone (user deleted it, or a duplicate event). Still make sure the media
            // database does not keep a dangling row pointing at it.
            scan(file)
            return true
        }

        queryMediaStoreUri(file)?.let { uri ->
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore delete failed for ${file.name}, falling back", e)
            }
        }

        if (!file.exists()) {
            scan(file)
            return true
        }

        val rawDeleted = try {
            file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Raw delete threw for ${file.name}", e)
            false
        }

        // file.delete() can report false on some FUSE implementations even when the unlink
        // succeeded, so trust the filesystem, not the return value.
        val gone = rawDeleted || !file.exists()
        if (gone) scan(file)
        return gone
    }

    /** Tells the media scanner the path is gone so the gallery drops its thumbnail immediately. */
    private fun scan(file: File) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeFor(file)),
                null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Media scan failed for ${file.name}", e)
        }
    }

    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    /**
     * Finds the media row for [file], matched on the absolute path only.
     *
     * DATA is deprecated but it is the one column that identifies a row unambiguously. Matching on
     * DISPLAY_NAME instead would be a data-loss bug: two folders can hold the same file name, and
     * deleting that row would delete somebody else's picture. If this ever returns null the caller
     * still unlinks the file directly and rescans, which is enough on its own.
     */
    private fun queryMediaStoreUri(file: File): Uri? = try {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DATA}=?",
            arrayOf(file.absolutePath),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "MediaStore query failed", e)
        null
    }

    private companion object {
        const val TAG = "DeletionHelper"
    }
}
