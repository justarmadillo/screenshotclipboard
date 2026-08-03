package com.douhi.screehshotcopy.deletion

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File

class DeletionHelper(private val context: Context) {

    fun delete(file: File): Boolean {
        val uri = queryMediaStoreUri(file)
        if (uri != null) {
            try {
                val rows = context.contentResolver.delete(uri, null, null)
                if (rows > 0) return true
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore delete failed, falling back to raw delete", e)
            }
        }
        val deleted = file.delete()
        if (deleted) {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeFor(file)),
                null,
            )
        }
        return deleted
    }

    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        else -> "image/jpeg"
    }

    private fun queryMediaStoreUri(file: File): Uri? {
        return try {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DATA}=?",
                arrayOf(file.absolutePath),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed", e)
            null
        }
    }

    private companion object {
        const val TAG = "DeletionHelper"
    }
}
