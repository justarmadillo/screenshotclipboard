package com.douhi.screehshotcopy.data

data class AppSettings(
    val enabled: Boolean = false,
    val folderPath: String = DEFAULT_FOLDER,
    val keepTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_FOLDER = "Pictures/Screenshots"
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val MIN_TIMEOUT_MS = 3_000L
        const val MAX_TIMEOUT_MS = 3_600_000L

        fun sanitizeTimeout(ms: Long): Long = when {
            ms <= 0L -> DEFAULT_TIMEOUT_MS
            else -> ms.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        }

        /**
         * Normalises a folder path relative to external storage root. Rejects anything that
         * could escape the storage root or is otherwise unusable, falling back to the default.
         */
        fun sanitizeFolder(raw: String?): String {
            val trimmed = raw?.trim()?.replace('\\', '/')?.trim('/') ?: return DEFAULT_FOLDER
            if (trimmed.isEmpty()) return DEFAULT_FOLDER
            val segments = trimmed.split('/').filter { it.isNotEmpty() && it != "." }
            if (segments.isEmpty() || segments.any { it == ".." }) return DEFAULT_FOLDER
            return segments.joinToString("/")
        }
    }
}
