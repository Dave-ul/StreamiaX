package eu.kanade.tachiyomi.source.novel.js

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class ChapterPagesTest {

    private fun full(page: Int) = (1..5).map { "p$page-$it" }

    @Test
    fun `known pages are all loaded`() = runTest {
        val loaded = mutableListOf<Int>()
        val chapters = collectChapterPages(full(1), totalPages = 3) { page ->
            loaded += page
            LoadedPage(full(page))
        }

        assertEquals(listOf(2, 3), loaded)
        assertEquals(full(1) + full(2) + full(3), chapters)
    }

    @Test
    fun `with known pages a failed page fails the whole list`() = runTest {
        assertThrows<IllegalStateException> {
            collectChapterPages(full(1), totalPages = 3) { page ->
                if (page == 2) error("page 2 broke")
                LoadedPage(full(page))
            }
        }
    }

    @Test
    fun `guessing ends at a plugin error, an empty or a short page`() = runTest {
        val ended = mutableListOf<Int>()
        val byError = collectChapterPages(emptyList(), totalPages = null, onGuessEnd = { page, _ -> ended += page }) {
            if (it == 3) error("no page 3")
            LoadedPage(full(it))
        }
        val byEmpty = collectChapterPages(emptyList(), totalPages = null) {
            LoadedPage(if (it == 2) emptyList() else full(it))
        }
        val byShort = collectChapterPages(emptyList(), totalPages = null) {
            LoadedPage(if (it == 2) listOf("last") else full(it))
        }

        assertEquals(full(1) + full(2), byError)
        assertEquals(listOf(3), ended)
        assertEquals(full(1), byEmpty)
        assertEquals(full(1) + "last", byShort)
    }

    @Test
    fun `guessing stops at 50 pages`() = runTest {
        var last = 0
        collectChapterPages(emptyList(), totalPages = null) { page ->
            last = page
            LoadedPage(full(page))
        }
        assertEquals(50, last)
    }

    @Test
    fun `a timeout or a network failure while guessing is never the end of the list`() = runTest {
        assertThrows<JsTimeoutException> {
            collectChapterPages(emptyList(), totalPages = null) {
                if (it == 2) throw JsTimeoutException("slow")
                LoadedPage(full(it))
            }
        }
        assertThrows<JsNetworkException> {
            collectChapterPages(emptyList(), totalPages = null) {
                if (it == 2) throw JsNetworkException("down", IOException("HTTP 429"))
                LoadedPage(full(it))
            }
        }
    }

    @Test
    fun `an empty page the plugin made of a network failure fails the list`() = runTest {
        val guessed = assertThrows<IOException> {
            collectChapterPages(emptyList(), totalPages = null) {
                if (it == 2) LoadedPage(emptyList(), IOException("HTTP 503")) else LoadedPage(full(it))
            }
        }
        val known = assertThrows<IOException> {
            collectChapterPages(full(1), totalPages = 3) {
                if (it == 2) LoadedPage(emptyList(), IOException("HTTP 503")) else LoadedPage(full(it))
            }
        }
        assertEquals("HTTP 503", guessed.message)
        assertEquals("HTTP 503", known.message)
    }

    @Test
    fun `with known pages a full page after a recovered network failure is kept`() = runTest {
        val chapters = collectChapterPages(full(1), totalPages = 2) {
            LoadedPage(full(it), IOException("first mirror down"))
        }
        assertEquals(full(1) + full(2), chapters)
    }

    @Test
    fun `cancellation is passed on`() = runTest {
        assertThrows<CancellationException> {
            collectChapterPages(emptyList(), totalPages = null) {
                if (it == 2) throw CancellationException("left the screen")
                LoadedPage(full(it))
            }
        }
    }
}
