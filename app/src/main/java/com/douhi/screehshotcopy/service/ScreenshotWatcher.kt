package com.douhi.screehshotcopy.service

import android.os.FileObserver
import java.io.File

class ScreenshotWatcher(
    private val folder: File,
    private val onScreenshot: (File) -> Unit,
) : FileObserver(folder.absolutePath, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) {

    private val seen = LinkedHashSet<String>()

    override fun onEvent(event: Int, path: String?) {
        if (path == null) return
        if (event and (FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) == 0) return
        val name = path.substringAfterLast('/')
        if (!IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }) return
        synchronized(seen) {
            if (!seen.add(name)) return
            if (seen.size > MAX_SEEN) seen.remove(seen.first())
        }
        onScreenshot(File(folder, name))
    }

    private companion object {
        val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif")
        const val MAX_SEEN = 256
    }
}
