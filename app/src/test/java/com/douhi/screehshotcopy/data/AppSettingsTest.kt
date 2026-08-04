package com.douhi.screehshotcopy.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SanitizeFolderTest {

    @Test
    fun `keeps a normal relative path`() {
        assertEquals("Pictures/Screenshots", AppSettings.sanitizeFolder("Pictures/Screenshots"))
    }

    @Test
    fun `strips surrounding and duplicated slashes`() {
        assertEquals("Pictures/Screenshots", AppSettings.sanitizeFolder("/Pictures//Screenshots/"))
    }

    @Test
    fun `normalises backslashes and dot segments`() {
        assertEquals("DCIM/Screenshots", AppSettings.sanitizeFolder("DCIM\\./Screenshots"))
    }

    @Test
    fun `refuses to escape the storage root`() {
        // A traversal would point the watcher — and therefore the deleter — outside the volume.
        assertEquals(AppSettings.DEFAULT_FOLDER, AppSettings.sanitizeFolder("../../data"))
        assertEquals(AppSettings.DEFAULT_FOLDER, AppSettings.sanitizeFolder("Pictures/../../etc"))
    }

    @Test
    fun `falls back to the default for empty or missing input`() {
        assertEquals(AppSettings.DEFAULT_FOLDER, AppSettings.sanitizeFolder(null))
        assertEquals(AppSettings.DEFAULT_FOLDER, AppSettings.sanitizeFolder(""))
        assertEquals(AppSettings.DEFAULT_FOLDER, AppSettings.sanitizeFolder("   "))
        assertEquals(AppSettings.DEFAULT_FOLDER, AppSettings.sanitizeFolder("///"))
    }
}

class SanitizeTimeoutTest {

    @Test
    fun `keeps a value inside the range`() {
        assertEquals(20_000L, AppSettings.sanitizeTimeout(20_000L))
    }

    @Test
    fun `clamps to the bounds`() {
        assertEquals(AppSettings.MIN_TIMEOUT_MS, AppSettings.sanitizeTimeout(1L))
        assertEquals(AppSettings.MAX_TIMEOUT_MS, AppSettings.sanitizeTimeout(Long.MAX_VALUE))
    }

    @Test
    fun `treats zero and negatives as unset rather than instant deletion`() {
        // A zero here would delete the screenshot before the prompt could even be tapped.
        assertEquals(AppSettings.DEFAULT_TIMEOUT_MS, AppSettings.sanitizeTimeout(0L))
        assertEquals(AppSettings.DEFAULT_TIMEOUT_MS, AppSettings.sanitizeTimeout(-1L))
    }
}
