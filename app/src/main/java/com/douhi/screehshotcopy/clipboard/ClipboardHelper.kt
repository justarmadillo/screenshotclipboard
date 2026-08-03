package com.douhi.screehshotcopy.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CopyResult(
    val ok: Boolean,
    val source: String = "",
    val uri: Uri? = null,
    val mime: String = "",
    val clipMimes: String = "",
)

class ClipboardHelper(private val context: Context) {

    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val copyLock = Mutex()
    private var lastFailure: String = ""

    /**
     * Copies [file] into the app's private cache and puts it on the clipboard.
     * Whole operation is serialized so concurrent screenshots can never corrupt
     * each other's cache copy.
     */
    suspend fun copyToClipboard(file: File): CopyResult = copyLock.withLock {
        lastFailure = ""
        val copy = copyToCache(file)
        if (copy == null) return@withLock CopyResult(false, lastFailure.ifEmpty { "copy failed" })
        try {
            val intent = Intent(Intent.ACTION_SEND)
                .setType(copy.second)
                .putExtra(Intent.EXTRA_STREAM, copy.first)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val item = ClipData.Item(null, intent, copy.first)
            val clip = ClipData(LABEL, arrayOf(copy.second, ClipDescription.MIMETYPE_TEXT_INTENT), item)
            clipboard.setPrimaryClip(clip)
            val clipMimes = clipboard.primaryClipDescription?.let { description ->
                (0 until description.mimeTypeCount).joinToString(",") { description.getMimeType(it) }
            } ?: "none"
            CopyResult(true, copy.third, copy.first, copy.second, clipMimes)
        } catch (e: Exception) {
            Log.w(TAG, "setPrimaryClip failed", e)
            CopyResult(false, "exception: ${e.message}")
        }
    }

    suspend fun testCopy(): CopyResult {
        val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
        val testFile = File(dir, "test_${System.currentTimeMillis()}.jpg")
        return try {
            val bitmap = android.graphics.Bitmap.createBitmap(800, 400, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.RED)
                java.io.FileOutputStream(testFile).use { output ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, output)
                }
                if (testFile.length() == 0L) {
                    CopyResult(false, "test file empty")
                } else {
                    copyToClipboard(testFile)
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                runCatching { testFile.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "test copy failed", e)
            CopyResult(false, "exception: ${e.message}")
        }
    }

    private fun fail(reason: String): Triple<Uri, String, String>? {
        lastFailure = reason
        return null
    }

    /**
     * Copies the screenshot into the app's private cache and exposes it via FileProvider.
     * Cache files are never indexed by the media scanner, so nothing about the copy ever
     * shows up in the gallery. The receiving app reads the bytes from the granted URI when
     * the user pastes.
     */
    private suspend fun copyToCache(file: File): Triple<Uri, String, String>? {
        return try {
            // A screenshot may fire CLOSE_WRITE while the file is still being settled.
            var attempts = 0
            while (attempts++ < SOURCE_RETRY_TIMES && (!file.exists() || file.length() == 0L)) {
                delay(SOURCE_RETRY_MS)
            }
            if (!file.isFile || file.length() == 0L) {
                return fail("source missing or empty: ${file.name}")
            }
            val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
            val mime = mimeFor(file)
            val copy = File(dir, "shot_${file.name.hashCode()}_${System.currentTimeMillis()}.${extensionFor(mime)}")
            file.inputStream().use { input ->
                copy.outputStream().use { output -> input.copyTo(output) }
            }
            if (copy.length() == 0L) return fail("empty file after copy")
            pruneCacheCopiesExcept(dir, copy)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", copy)
            Triple(uri, mime, "cache")
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider copy failed", e)
            fail("cache: ${e.message}")
        }
    }

    /**
     * Removes stale clipboard copies. Called only after the new copy has been fully
     * written, and it never deletes the freshly written file, so a concurrent copy can
     * never be truncated to empty.
     */
    private fun pruneCacheCopiesExcept(dir: File, keep: File) {
        dir.listFiles()?.forEach { old ->
            if (old.isFile && old.name != keep.name) runCatching { old.delete() }
        }
    }

    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        "heic", "heif" -> "image/heic"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        "image/bmp" -> "bmp"
        "image/jpeg" -> "jpg"
        else -> "jpg"
    }

    private companion object {
        const val TAG = "ClipboardHelper"
        const val LABEL = "Screenshot"
        const val SOURCE_RETRY_TIMES = 5
        const val SOURCE_RETRY_MS = 150L
    }
}