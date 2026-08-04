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

    private val clipboard: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private val copyLock = Mutex()

    /**
     * Copies [file] into the app's private cache and puts that copy on the clipboard.
     *
     * The clipboard must point at a copy, not at the screenshot: the whole point of the app is
     * that the original gets deleted, and a clipboard entry pointing at a deleted file pastes
     * nothing. Cache files are never seen by the media scanner, so the copy never shows up in the
     * gallery.
     *
     * The whole operation is serialised so two screenshots landing at once cannot interleave.
     */
    suspend fun copyToClipboard(file: File): CopyResult = copyLock.withLock {
        val manager = clipboard ?: return@withLock CopyResult(false, "clipboard unavailable")
        val staged = stageInCache(file)
        if (staged.isFailure) {
            return@withLock CopyResult(false, staged.exceptionOrNull()?.message ?: "copy failed")
        }
        val (uri, mime) = staged.getOrThrow()
        try {
            val intent = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val item = ClipData.Item(null, intent, uri)
            val clip = ClipData(LABEL, arrayOf(mime, ClipDescription.MIMETYPE_TEXT_INTENT), item)
            manager.setPrimaryClip(clip)
            CopyResult(true, "cache", uri, mime, readClipMimes(manager))
        } catch (e: Exception) {
            Log.w(TAG, "setPrimaryClip failed", e)
            CopyResult(false, "exception: ${e.message}")
        }
    }

    /**
     * Reading the clipboard back is diagnostics only. Android restricts clipboard reads for
     * non-focused apps, so a null answer here says nothing about whether the write worked and
     * must never be treated as failure.
     */
    private fun readClipMimes(manager: ClipboardManager): String = try {
        manager.primaryClipDescription?.let { description ->
            (0 until description.mimeTypeCount).joinToString(",") { description.getMimeType(it) }
        } ?: "unreadable"
    } catch (e: Exception) {
        "unreadable"
    }

    suspend fun testCopy(): CopyResult {
        val dir = cacheDir() ?: return CopyResult(false, "cache dir unavailable")
        val testFile = File(dir, "test_${System.currentTimeMillis()}.jpg")
        return try {
            val bitmap = android.graphics.Bitmap.createBitmap(800, 400, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.RED)
                java.io.FileOutputStream(testFile).use { output ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, output)
                }
                if (testFile.length() == 0L) CopyResult(false, "test file empty") else copyToClipboard(testFile)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                runCatching { testFile.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "test copy failed", e)
            CopyResult(false, "exception: ${e.message}")
        }
    }

    /** Drops leftovers from a previous run. Called when the service starts. */
    fun pruneCache() {
        val dir = cacheDir() ?: return
        pruneKeepingNewest(dir, keep = null)
    }

    private fun cacheDir(): File? = try {
        File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }.takeIf { it.isDirectory }
    } catch (e: Exception) {
        Log.w(TAG, "Cache dir unavailable", e)
        null
    }

    private suspend fun stageInCache(file: File): Result<Pair<Uri, String>> = try {
        if (!awaitStableFile(file)) {
            Result.failure(IllegalStateException("source missing or empty: ${file.name}"))
        } else {
            val dir = cacheDir() ?: throw IllegalStateException("cache dir unavailable")
            val mime = mimeFor(file)
            val copy = File(dir, "shot_${System.currentTimeMillis()}_${file.name.hashCode()}.${extensionFor(mime)}")
            file.inputStream().use { input ->
                copy.outputStream().use { output -> input.copyTo(output) }
            }
            if (copy.length() == 0L) {
                runCatching { copy.delete() }
                throw IllegalStateException("empty file after copy")
            }
            pruneKeepingNewest(dir, keep = copy)
            val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, copy)
            Result.success(uri to mime)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Staging ${file.name} failed", e)
        Result.failure(e)
    }

    /**
     * Waits until the screenshot has stopped growing.
     *
     * CLOSE_WRITE normally means the writer is done, but MOVED_TO and the MediaStore detector can
     * both surface a file mid-write. Copying then would put a truncated image on the clipboard,
     * which is worse than waiting a few hundred milliseconds.
     */
    private suspend fun awaitStableFile(file: File): Boolean {
        var lastSize = -1L
        repeat(STABILITY_ATTEMPTS) {
            val size = try {
                if (file.isFile) file.length() else -1L
            } catch (e: Exception) {
                -1L
            }
            if (size > 0L && size == lastSize) return true
            lastSize = size
            delay(STABILITY_INTERVAL_MS)
        }
        return try {
            file.isFile && file.length() > 0L
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Trims the staging directory to the newest few files.
     *
     * Older cache copies are kept rather than deleted outright: an app that pasted a moment ago may
     * still be resolving the previous URI, and pulling the file out from under it turns a paste
     * into a blank. [keep] is never removed.
     */
    private fun pruneKeepingNewest(dir: File, keep: File?) {
        try {
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MS
            // keep == null is the startup sweep, where nothing is being staged, so the budget is
            // the full MAX_CACHE_COPIES rather than MAX_CACHE_COPIES - 1.
            val budget = if (keep == null) MAX_CACHE_COPIES else MAX_CACHE_COPIES - 1
            val candidates = files
                .filter { keep == null || it.name != keep.name }
                .sortedByDescending { it.lastModified() }
            candidates.forEachIndexed { index, stale ->
                if (index >= budget || stale.lastModified() < cutoff) runCatching { stale.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache prune failed", e)
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
        else -> "jpg"
    }

    private companion object {
        const val TAG = "ClipboardHelper"
        const val LABEL = "Screenshot"
        const val CACHE_SUBDIR = "clipboard"
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
        const val MAX_CACHE_COPIES = 3
        const val MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L
        const val STABILITY_ATTEMPTS = 12
        const val STABILITY_INTERVAL_MS = 120L
    }
}
