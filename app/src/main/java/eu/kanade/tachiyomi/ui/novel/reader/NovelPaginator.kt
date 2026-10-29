package eu.kanade.tachiyomi.ui.novel.reader

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/** A slice of paragraph [paragraph], from character [start] (inclusive) to [end] (exclusive). */
data class PageSlice(val paragraph: Int, val start: Int, val end: Int)

/** One screen of text; never empty. */
data class NovelPage(val slices: List<PageSlice>) {
    init {
        require(slices.isNotEmpty()) { "A page needs at least one slice" }
    }

    val firstParagraph: Int get() = slices.first().paragraph
}

/** The line metrics pagination needs from a laid-out paragraph. */
interface ParagraphLines {
    val lineCount: Int
    fun lineTop(line: Int): Float
    fun lineBottom(line: Int): Float
    fun lineStart(line: Int): Int
    fun lineEnd(line: Int): Int
}

/**
 * Splits [paragraphs] into pages that fit [width] x [height] pixels, cutting long paragraphs
 * at line breaks. Paragraphs are separated by [gap] pixels, as in the scrolling reader.
 * Same idea as Dartotsu's paged novel mode (lib/Screen/Reader/Engine/NovelPaginate.dart).
 */
fun paginate(
    paragraphs: List<String>,
    measurer: TextMeasurer,
    style: TextStyle,
    width: Int,
    height: Int,
    gap: Int,
): List<NovelPage> {
    if (width <= 0) return emptyList()
    return paginate(paragraphs.size, height, gap) { index ->
        measurer.measure(paragraphs[index], style, constraints = Constraints(maxWidth = width)).toParagraphLines()
    }
}

/** Pagination over already measured paragraphs; [measure] lays out paragraph `index`. */
internal fun paginate(count: Int, height: Int, gap: Int, measure: (index: Int) -> ParagraphLines): List<NovelPage> {
    if (height <= 0) return emptyList()
    val pages = mutableListOf<NovelPage>()
    var current = mutableListOf<PageSlice>()
    var used = 0f

    fun flush() {
        if (current.isNotEmpty()) pages += NovelPage(current)
        current = mutableListOf()
        used = 0f
    }

    for (index in 0 until count) {
        val layout = measure(index)
        var line = 0
        while (line < layout.lineCount) {
            val spacing = if (current.isEmpty()) 0 else gap
            val available = height - used - spacing
            val top = layout.lineTop(line)
            var last = line
            while (last < layout.lineCount && layout.lineBottom(last) - top <= available) last++
            if (last == line) {
                // Not even one line fits: start a new page, or force one line on an empty page.
                if (current.isNotEmpty()) {
                    flush()
                    continue
                }
                last = line + 1
            }
            current += PageSlice(index, layout.lineStart(line), layout.lineEnd(last - 1))
            used += spacing + layout.lineBottom(last - 1) - top
            line = last
            if (line < layout.lineCount) flush()
        }
    }
    flush()
    return pages
}

/**
 * The page where [paragraph] starts, so a re-pagination (new font size, rotation) never
 * lands in the middle of the paragraph being read; else the last page before it.
 */
fun pageOfParagraph(pages: List<NovelPage>, paragraph: Int): Int =
    pages.indexOfFirst { page -> page.slices.any { it.paragraph == paragraph } }
        .takeIf { it >= 0 }
        ?: pages.indexOfLast { it.firstParagraph <= paragraph }.coerceAtLeast(0)

private fun TextLayoutResult.toParagraphLines() = object : ParagraphLines {
    override val lineCount: Int get() = this@toParagraphLines.lineCount
    override fun lineTop(line: Int) = getLineTop(line)
    override fun lineBottom(line: Int) = getLineBottom(line)
    override fun lineStart(line: Int) = getLineStart(line)
    override fun lineEnd(line: Int) = getLineEnd(line)
}
