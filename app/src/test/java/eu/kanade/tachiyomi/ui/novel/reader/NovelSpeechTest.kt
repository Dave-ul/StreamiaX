package eu.kanade.tachiyomi.ui.novel.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelSpeechTest {

    @Test
    fun `short paragraphs are spoken whole`() {
        assertEquals(listOf("Hello there."), NovelSpeech.splitForSpeech("Hello there.", 100))
    }

    @Test
    fun `long paragraphs are cut at sentence ends`() {
        val text = "First sentence here. Second sentence here. Third sentence here."

        val parts = NovelSpeech.splitForSpeech(text, 45)

        assertEquals(listOf("First sentence here. Second sentence here.", "Third sentence here."), parts)
    }

    @Test
    fun `text without sentence ends is cut at spaces and fits the limit`() {
        val text = (1..50).joinToString(" ") { "word$it" }

        val parts = NovelSpeech.splitForSpeech(text, 40)

        assertTrue(parts.all { it.length <= 40 })
        assertEquals(text, parts.joinToString(" "))
    }
}
