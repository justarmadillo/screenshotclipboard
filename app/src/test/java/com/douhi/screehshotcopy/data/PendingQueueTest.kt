package com.douhi.screehshotcopy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDeletionCodecTest {

    @Test
    fun `round trips a plain entry`() {
        val entry = PendingDeletion(1234, 1_700_000_000_000L, "/storage/emulated/0/Pictures/Screenshots/a.png")
        assertEquals(entry, PendingDeletion.decode(entry.encode()))
    }

    @Test
    fun `round trips a path containing separators and spaces`() {
        val entry = PendingDeletion(7, 42L, "/storage/emulated/0/Pictures/My Shots/a|b:c 2024-01-01.png")
        assertEquals(entry, PendingDeletion.decode(entry.encode()))
    }

    @Test
    fun `rejects malformed records instead of throwing`() {
        // A corrupt or hand-edited store must degrade to "not tracked", never to an exception on
        // every read for the life of the install.
        val sep = "\u001F"
        assertNull(PendingDeletion.decode(""))
        assertNull(PendingDeletion.decode("only-one-field"))
        assertNull(PendingDeletion.decode("notanint${sep}123${sep}/a.png"))
        assertNull(PendingDeletion.decode("1${sep}notalong${sep}/a.png"))
        assertNull(PendingDeletion.decode("1${sep}123$sep"))
    }

}

class PendingQueueMergeTest {

    private fun entry(id: Int, deadline: Long, path: String) = PendingDeletion(id, deadline, path)

    @Test
    fun `adds a new entry with the supplied notification id`() {
        val (merged, added) = PendingQueue.merge(emptyList(), "/a.png", 100L, newNotifId = 1000, maxEntries = 10)
        assertEquals(listOf(entry(1000, 100L, "/a.png")), merged)
        assertEquals(entry(1000, 100L, "/a.png"), added)
    }

    @Test
    fun `re-adding a path reuses its notification id and refreshes the deadline`() {
        val existing = listOf(entry(1000, 100L, "/a.png"))
        val (merged, added) = PendingQueue.merge(existing, "/a.png", 500L, newNotifId = 2000, maxEntries = 10)
        assertEquals(1, merged.size)
        assertEquals(1000, added.notifId)
        assertEquals(500L, added.deadlineMs)
    }

    @Test
    fun `keeps entries sorted by deadline`() {
        var list = PendingQueue.merge(emptyList(), "/c.png", 300L, 1002, 10).first
        list = PendingQueue.merge(list, "/a.png", 100L, 1000, 10).first
        list = PendingQueue.merge(list, "/b.png", 200L, 1001, 10).first
        assertEquals(listOf("/a.png", "/b.png", "/c.png"), list.map { it.path })
    }

    @Test
    fun `evicts the oldest entries when the cap is exceeded but never the new one`() {
        val existing = (1..3).map { entry(1000 + it, it * 100L, "/old$it.png") }
        val (merged, added) = PendingQueue.merge(existing, "/new.png", 9_999L, newNotifId = 2000, maxEntries = 3)
        assertEquals(3, merged.size)
        assertTrue(merged.contains(added))
        // The two lowest deadlines are the ones dropped.
        assertEquals(listOf("/old2.png", "/old3.png", "/new.png"), merged.map { it.path })
    }
}

class PendingQueueDueTest {

    private val maxTimeout = 3_600_000L

    private fun entry(deadline: Long) = PendingDeletion(1000, deadline, "/a$deadline.png")

    @Test
    fun `returns entries whose deadline has passed`() {
        val entries = listOf(entry(50L), entry(150L))
        assertEquals(listOf(entry(50L)), PendingQueue.dueAt(entries, now = 100L, maxTimeoutMs = maxTimeout))
    }

    @Test
    fun `treats the exact deadline as due`() {
        assertEquals(1, PendingQueue.dueAt(listOf(entry(100L)), now = 100L, maxTimeoutMs = maxTimeout).size)
    }

    @Test
    fun `leaves future entries alone`() {
        assertTrue(PendingQueue.dueAt(listOf(entry(5_000L)), now = 100L, maxTimeoutMs = maxTimeout).isEmpty())
    }

    @Test
    fun `reclaims entries stranded beyond the horizon by a backwards clock jump`() {
        val now = 1_000L
        val stranded = entry(now + maxTimeout + PendingQueue.CLOCK_SKEW_SLACK_MS + 1)
        assertEquals(listOf(stranded), PendingQueue.dueAt(listOf(stranded), now, maxTimeout))
    }

    @Test
    fun `does not reclaim an entry sitting exactly on the horizon`() {
        val now = 1_000L
        val edge = entry(now + maxTimeout + PendingQueue.CLOCK_SKEW_SLACK_MS)
        assertTrue(PendingQueue.dueAt(listOf(edge), now, maxTimeout).isEmpty())
    }
}

class PendingQueueNotifIdTest {

    @Test
    fun `increments within the range`() {
        assertEquals(1001, PendingQueue.nextNotifId(1000, base = 1000, max = 1003))
    }

    @Test
    fun `wraps at the top of the range`() {
        assertEquals(1000, PendingQueue.nextNotifId(1003, base = 1000, max = 1003))
    }

    @Test
    fun `resets a value that fell below the range`() {
        // Guards against a corrupt or hand-edited preferences file producing ids that could
        // collide with the foreground service notification.
        assertEquals(1000, PendingQueue.nextNotifId(0, base = 1000, max = 1003))
        assertEquals(1000, PendingQueue.nextNotifId(-5, base = 1000, max = 1003))
    }
}
