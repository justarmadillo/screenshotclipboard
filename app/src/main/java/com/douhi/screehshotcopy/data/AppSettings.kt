package com.douhi.screehshotcopy.data

enum class DeleteBehavior { KEEP, DELETE }

data class AppSettings(
    val enabled: Boolean = false,
    val folderPath: String = DEFAULT_FOLDER,
    val deleteBehavior: DeleteBehavior = DeleteBehavior.DELETE,
    val deleteDelayMs: Long = DEFAULT_DELAY_MS,
) {
    companion object {
        const val DEFAULT_FOLDER = "Pictures/Screenshots"
        const val DEFAULT_DELAY_MS = 20_000L
    }
}
