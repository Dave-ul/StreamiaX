package eu.kanade.tachiyomi.ui.novel.reader

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/** A slice of paragraph [paragraph], from character [start] (inclusive) to [end] (exclusive). */
data class PageSlice(val paragraph: Int, val start: Int, val end: Int)

data class NovelPage(val slices: List<PageSlice>) {
    val firstParagraph: Int get() = slices.first().paragraph
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
    if (width <= 0 || height <= 0) return emptyList()
    val pages = mutableListOf<NovelPage>()
    var current = mutableListOf<PageSlice>()
    var used = 0f

    fun flush() {
        if (current.isNotEmpty()) pages += NovelPage(current)
        current = mutableListOf()
        used = 0f
    }

    paragraphs.forEachIndexed { index, text ->
        val layout = measurer.measure(text, style, constraints = Constraints(maxWidth = width))
        var line = 0
        while (line < layout.lineCount) {
            val spacing = if (current.isEmpty()) 0 else gap
            val available = height - used - spacing
            val top = layout.getLineTop(line)
            var last = line
            while (last < layout.lineCount && layout.getLineBottom(last) - top <= available) last++
            if (last == line) {
                // Not even one line fits: start a new page, or force one line on an empty page.
                if (current.isNotEmpty()) {
                    flush()
                    continue
                }
                last = line + 1
            }
            current += PageSlice(index, layout.getLineStart(line), layout.getLineEnd(last - 1))
            used += spacing + layout.getLineBottom(last - 1) - top
            line = last
            if (line < layout.lineCount) flush()
        }
    }
    flush()
    return pages
}
