package eu.kanade.tachiyomi.ui.novel.reader

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelTranslatorTest {

    @Test
    fun `batch answers with auto detection keep paragraph order`() {
        // Captured from clients5.google.com/translate_a/t with two q parameters.
        val body = """[["L'eroe estrasse la spada.","en"],["Il drago ruggì.","en"]]"""

        val result = NovelTranslator.parseBatch(Json.parseToJsonElement(body))

        assertEquals(listOf("L'eroe estrasse la spada.", "Il drago ruggì."), result)
    }

    @Test
    fun `batch answers with a fixed source language are plain strings`() {
        val result = NovelTranslator.parseBatch(Json.parseToJsonElement("""["Ciao","Mondo"]"""))

        assertEquals(listOf("Ciao", "Mondo"), result)
    }

    @Test
    fun `gtx answers join all translated sentences`() {
        val body = """[[["Ciao. ","Hello. ",null,null,10],["Come stai?","How are you?",null,null,10]],null,"en"]"""

        assertEquals("Ciao. Come stai?", NovelTranslator.parseGtx(Json.parseToJsonElement(body)))
    }

    @Test
    fun `paragraphs are grouped under the batch size`() {
        val long = "a".repeat(3000)
        val short = "b".repeat(1500)

        val batches = NovelTranslator.batches(listOf(long, short, short, short))

        assertEquals(listOf(listOf(long), listOf(short, short), listOf(short)), batches)
    }

    @Test
    fun `language codes are mapped to Google's`() {
        assertEquals("zh-CN", NovelTranslator.googleCode("zh-Hans"))
        assertEquals("iw", NovelTranslator.googleCode("he"))
        assertEquals("it", NovelTranslator.googleCode("it"))
    }
}
