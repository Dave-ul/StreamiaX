package eu.kanade.tachiyomi.source.novel.js

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepeatedPageGuardTest {

    private val guard = RepeatedPageGuard()

    @Test
    fun `the same novels again on the next page are a repeat`() {
        assertFalse(guard.isRepeat("popular", 1, listOf("/a", "/b")))
        assertTrue(guard.isRepeat("popular", 2, listOf("/a", "/b")))
    }

    @Test
    fun `new novels, a reloaded first page or another listing are not`() {
        assertFalse(guard.isRepeat("popular", 1, listOf("/a")))
        assertFalse(guard.isRepeat("popular", 2, listOf("/b")))
        assertFalse(guard.isRepeat("popular", 1, listOf("/b")))
        assertFalse(guard.isRepeat("search:x", 2, listOf("/b")))
        assertFalse(guard.isRepeat("search:x", 3, emptyList()))
        assertFalse(guard.isRepeat("search:x", 4, emptyList()))
    }

    @Test
    fun `a page loaded again is not a repeat`() {
        assertFalse(guard.isRepeat("popular", 1, listOf("/a")))
        assertFalse(guard.isRepeat("popular", 2, listOf("/b")))
        // A retry or a refresh of page 2
        assertFalse(guard.isRepeat("popular", 2, listOf("/b")))
        assertTrue(guard.isRepeat("popular", 3, listOf("/b")))
    }
}
