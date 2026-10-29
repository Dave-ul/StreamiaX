package eu.kanade.tachiyomi.ui.novel.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelPaginatorTest {

    /** A paragraph of [lines] lines, each [height] px tall and [chars] characters long. */
    private fun lines(lines: Int, height: Float = 10f, chars: Int = 10) = object : ParagraphLines {
        override val lineCount = lines
        override fun lineTop(line: Int) = line * height
        override fun lineBottom(line: Int) = (line + 1) * height
        override fun lineStart(line: Int) = line * chars
        override fun lineEnd(line: Int) = (line + 1) * chars
    }

    @Test
    fun `short paragraphs share a page, separated by the gap`() {
        // 2 + 2 lines = 40px, plus a 5px gap: fits in 50px.
        val pages = paginate(count = 2, height = 50, gap = 5) { lines(2) }

        assertEquals(listOf(NovelPage(listOf(PageSlice(0, 0, 20), PageSlice(1, 0, 20)))), pages)
    }

    @Test
    fun `a long paragraph is cut at a line break and continues on the next page`() {
        val pages = paginate(count = 1, height = 30, gap = 5) { lines(5) }

        assertEquals(
            listOf(
                NovelPage(listOf(PageSlice(0, 0, 30))),
                NovelPage(listOf(PageSlice(0, 30, 50))),
            ),
            pages,
        )
    }

    @Test
    fun `a paragraph that does not fit after the gap moves to a new page`() {
        // Page height 25: first paragraph uses 20px, the gap leaves 0px for the second.
        val pages = paginate(count = 2, height = 25, gap = 5) { lines(2) }

        assertEquals(listOf(0, 1), pages.map { it.firstParagraph })
    }

    @Test
    fun `a line taller than the page is forced onto its own page`() {
        val pages = paginate(count = 1, height = 5, gap = 0) { lines(2, height = 10f) }

        assertEquals(2, pages.size)
        assertEquals(PageSlice(0, 0, 10), pages[0].slices.single())
    }

    @Test
    fun `no height or no paragraphs gives no pages`() {
        assertEquals(emptyList<NovelPage>(), paginate(count = 3, height = 0, gap = 5) { lines(1) })
        assertEquals(emptyList<NovelPage>(), paginate(count = 0, height = 100, gap = 5) { lines(1) })
    }

    @Test
    fun `the page of a paragraph is where it starts, not a continuation`() {
        val pages = listOf(
            NovelPage(listOf(PageSlice(0, 0, 10), PageSlice(1, 0, 30))),
            NovelPage(listOf(PageSlice(1, 30, 60))),
            NovelPage(listOf(PageSlice(2, 0, 10))),
        )

        assertEquals(0, pageOfParagraph(pages, 1))
        assertEquals(2, pageOfParagraph(pages, 2))
        assertEquals(2, pageOfParagraph(pages, 9))
    }
}
