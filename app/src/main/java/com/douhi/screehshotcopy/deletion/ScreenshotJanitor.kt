package com.douhi.screehshotcopy.deletion

import android.content.Context
import android.util.Log
import com.douhi.screehshotcopy.R
import com.douhi.screehshotcopy.data.PendingDeletion
import com.douhi.screehshotcopy.data.PendingRepository
import com.douhi.screehshotcopy.notify.Notifications
import com.douhi.screehshotcopy.util.StatusBus
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The single place where a pending screenshot is resolved, whichever component asks for it —
 * the service's deadline sweeper, the notification's Keep/Delete buttons, or the shutdown path.
 *
 * Removal from [PendingRepository] always happens first and is atomic, so an entry is resolved
 * exactly once no matter how many callers race for it.
 */
class ScreenshotJanitor(
    private val context: Context,
    private val pendingRepository: PendingRepository,
    private val deletionHelper: DeletionHelper,
) {

    /**
     * Empties the queue and clears its notifications without touching any file. Used when
     * monitoring is switched off: the user just told the app to stop managing screenshots, so
     * deleting them at that moment would be the opposite of what was asked.
     */
    suspend fun cancelAllPending() = withContext(NonCancellable) {
        pendingRepository.takeAll().forEach { Notifications.cancel(context, it.notifId) }
    }

    /**
     * User pressed Keep: stop tracking the file and leave it on disk.
     *
     * NonCancellable, like [deleteNow]: removing the entry is the claim, and being cancelled
     * between the claim and acting on it would leave the screenshot untracked.
     */
    suspend fun keep(path: String, notifId: Int) = withContext(NonCancellable) {
        val claimed = pendingRepository.remove(path) != null
        val name = File(path).name
        Notifications.cancel(context, notifId)
        if (!claimed) {
            // The deadline already fired and the file is gone; say so instead of lying.
            Log.i(TAG, "Keep arrived too late for $path")
            announce(notifId, context.getString(R.string.keep_too_late, name))
            return@withContext
        }
        announce(notifId, context.getString(R.string.kept, name))
    }

    /** User pressed Delete now: skip the remaining wait. */
    suspend fun deleteNow(path: String, notifId: Int) = withContext(NonCancellable) {
        val entry = pendingRepository.remove(path)
        if (entry == null) {
            Notifications.cancel(context, notifId)
            return@withContext
        }
        finalize(entry)
    }

    /**
     * Deadline reached (or the queue is being drained). [entry] must already have been removed
     * from the repository by the caller — that removal is what claims ownership of the delete.
     */
    fun finalize(entry: PendingDeletion) {
        val file = File(entry.path)
        Notifications.cancel(context, entry.notifId)
        val deleted = try {
            deletionHelper.delete(file)
        } catch (e: Exception) {
            Log.w(TAG, "Delete threw for ${entry.path}", e)
            false
        }
        val message =
            if (deleted) context.getString(R.string.deleted, file.name)
            else context.getString(R.string.delete_failed, file.name)
        announce(entry.notifId, message, alsoNotify = !deleted)
    }

    private fun announce(notifId: Int, message: String, alsoNotify: Boolean = true) {
        StatusBus.update { it.copy(lastAction = message) }
        if (alsoNotify) {
            Notifications.post(context, notifId, Notifications.buildResultNotification(context, message))
        }
    }

    private companion object {
        const val TAG = "ScreenshotJanitor"
    }
}
