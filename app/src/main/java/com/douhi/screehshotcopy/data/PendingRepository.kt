package com.douhi.screehshotcopy.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * One screenshot that has been copied to the clipboard and is waiting for the user to decide
 * whether to keep it. [deadlineMs] is wall-clock (System.currentTimeMillis) so it survives both
 * process death and reboot.
 */
data class PendingDeletion(
    val notifId: Int,
    val deadlineMs: Long,
    val path: String,
) {
    fun encode(): String = "$notifId$SEPARATOR$deadlineMs$SEPARATOR$path"

    companion object {
        /** ASCII unit separator: cannot occur in a POSIX file path. */
        private const val SEPARATOR = '\u001F'

        fun decode(raw: String): PendingDeletion? {
            val parts = raw.split(SEPARATOR, limit = 3)
            if (parts.size != 3) return null
            val id = parts[0].toIntOrNull() ?: return null
            val deadline = parts[1].toLongOrNull() ?: return null
            if (parts[2].isEmpty()) return null
            return PendingDeletion(id, deadline, parts[2])
        }
    }
}

/**
 * The queue's decision logic, kept free of DataStore and Android so it can be tested directly.
 * Everything that decides *which* screenshot gets deleted lives here.
 */
object PendingQueue {

    /** Wall-clock tolerance before a future deadline is treated as a backwards clock jump. */
    const val CLOCK_SKEW_SLACK_MS = 60_000L

    /**
     * Adds or replaces the entry for [path]. An existing entry keeps its notification id so a
     * duplicate detection updates the live prompt instead of stacking a second one.
     */
    fun merge(
        entries: List<PendingDeletion>,
        path: String,
        deadlineMs: Long,
        newNotifId: Int,
        maxEntries: Int,
    ): Pair<List<PendingDeletion>, PendingDeletion> {
        val existing = entries.firstOrNull { it.path == path }
        val entry = PendingDeletion(existing?.notifId ?: newNotifId, deadlineMs, path)
        val merged = entries.filter { it.path != path } + entry
        // Cap by dropping the oldest deadlines, but never the entry just added: the caller is
        // about to post a prompt for it and an untracked prompt could never be honoured.
        val overflow = merged.size - maxEntries
        if (overflow <= 0) return merged.sortedBy { it.deadlineMs } to entry
        val doomed = merged.filter { it.path != path }
            .sortedBy { it.deadlineMs }
            .take(overflow)
            .toSet()
        return (merged - doomed).sortedBy { it.deadlineMs } to entry
    }

    /**
     * Entries that must be acted on at [now]: those whose deadline has passed, plus any whose
     * deadline is further out than the longest window the app allows. The latter can only happen
     * if the system clock jumped backwards, and an entry that can never fire would strand both the
     * file and its notification forever.
     */
    fun dueAt(entries: List<PendingDeletion>, now: Long, maxTimeoutMs: Long): List<PendingDeletion> {
        val horizon = now + maxTimeoutMs + CLOCK_SKEW_SLACK_MS
        return entries.filter { it.deadlineMs <= now || it.deadlineMs > horizon }
    }

    /** Rolls the notification id through a bounded range so it never collides with the service's. */
    fun nextNotifId(current: Int, base: Int, max: Int): Int =
        if (current >= max || current < base) base else current + 1
}

/**
 * Durable queue of screenshots awaiting a keep/delete decision.
 *
 * Every mutation happens inside a single DataStore `edit`, and DataStore serialises edits per
 * file, so "take the due entries" and "the user pressed Keep" can never both act on the same
 * entry. That is what makes a Keep tap authoritative even when it lands microseconds before the
 * deadline, and what lets deletions survive the process being killed.
 */
class PendingRepository(private val dataStore: DataStore<Preferences>) {

    val pending: Flow<List<PendingDeletion>> = dataStore.data
        .catch { e ->
            Log.w(TAG, "Pending read failed, treating as empty", e)
            emit(emptyPreferences())
        }
        .map { prefs -> prefs.decodeEntries() }
        .distinctUntilChanged()

    suspend fun peek(): List<PendingDeletion> = try {
        pending.first()
    } catch (e: Exception) {
        Log.w(TAG, "Pending peek failed", e)
        emptyList()
    }

    /**
     * Registers [path] for deletion at `now + timeoutMs`. If the path is already tracked its
     * notification id is reused, so a duplicate detection updates the existing prompt instead of
     * stacking a second one.
     */
    suspend fun add(path: String, timeoutMs: Long): PendingDeletion? = try {
        val holder = arrayOfNulls<PendingDeletion>(1)
        dataStore.edit { prefs ->
            val (merged, entry) = PendingQueue.merge(
                entries = prefs.decodeEntries(),
                path = path,
                deadlineMs = System.currentTimeMillis() + timeoutMs,
                newNotifId = prefs.allocateNotifId(),
                maxEntries = MAX_ENTRIES,
            )
            prefs.writeEntries(merged)
            holder[0] = entry
        }
        holder[0]
    } catch (e: Exception) {
        Log.w(TAG, "Pending add failed for $path", e)
        null
    }

    /** Removes [path] from the queue. Returns the removed entry, or null if it was already gone. */
    suspend fun remove(path: String): PendingDeletion? = try {
        val holder = arrayOfNulls<PendingDeletion>(1)
        dataStore.edit { prefs ->
            val entries = prefs.decodeEntries()
            val match = entries.firstOrNull { it.path == path }
            if (match != null) {
                prefs.writeEntries(entries.filter { it.path != path })
                holder[0] = match
            }
        }
        holder[0]
    } catch (e: Exception) {
        Log.w(TAG, "Pending remove failed for $path", e)
        null
    }

    /** Atomically removes and returns every entry that is due at [now]. See [PendingQueue.dueAt]. */
    suspend fun takeDue(now: Long, maxTimeoutMs: Long): List<PendingDeletion> = try {
        val holder = arrayOfNulls<List<PendingDeletion>>(1)
        dataStore.edit { prefs ->
            val entries = prefs.decodeEntries()
            val due = PendingQueue.dueAt(entries, now, maxTimeoutMs)
            if (due.isNotEmpty()) prefs.writeEntries(entries - due.toSet())
            holder[0] = due
        }
        holder[0] ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "Pending takeDue failed", e)
        emptyList()
    }

    /** Empties the queue and returns what was in it. Used when monitoring is switched off. */
    suspend fun takeAll(): List<PendingDeletion> = try {
        val holder = arrayOfNulls<List<PendingDeletion>>(1)
        dataStore.edit { prefs ->
            val entries = prefs.decodeEntries()
            if (entries.isNotEmpty()) prefs.writeEntries(emptyList())
            holder[0] = entries
        }
        holder[0] ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "Pending takeAll failed", e)
        emptyList()
    }

    private fun Preferences.decodeEntries(): List<PendingDeletion> =
        (this[KEY_ENTRIES] ?: emptySet())
            .mapNotNull { PendingDeletion.decode(it) }
            .sortedBy { it.deadlineMs }

    private fun MutablePreferences.writeEntries(entries: List<PendingDeletion>) {
        this[KEY_ENTRIES] = entries.map { it.encode() }.toSet()
    }

    private fun MutablePreferences.allocateNotifId(): Int {
        val current = (this[KEY_NEXT_ID] ?: NOTIF_ID_BASE).coerceIn(NOTIF_ID_BASE, NOTIF_ID_MAX)
        this[KEY_NEXT_ID] = PendingQueue.nextNotifId(current, NOTIF_ID_BASE, NOTIF_ID_MAX)
        return current
    }

    private companion object {
        const val TAG = "PendingRepository"
        const val MAX_ENTRIES = 100
        const val NOTIF_ID_BASE = 1000
        const val NOTIF_ID_MAX = 101_000
        val KEY_ENTRIES = stringSetPreferencesKey("entries")
        val KEY_NEXT_ID = intPreferencesKey("next_notif_id")
    }
}
