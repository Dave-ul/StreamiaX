package eu.kanade.tachiyomi.ui.novel.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun `missing or blank batch items are failures, not text`() {
        assertThrows(IllegalArgumentException::class.java) {
            NovelTranslator.parseBatch(Json.parseToJsonElement("""[["Ciao","en"],[null,"en"]]"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelTranslator.parseBatch(Json.parseToJsonElement("""["Ciao",""]"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelTranslator.parseGtx(Json.parseToJsonElement("""[[[null,"Hello"]]]"""))
        }
    }

    @Test
    fun `repeated paragraphs are sent once and cached, text without letters is kept`() = runBlocking {
        val server = FakeTranslate { texts -> texts.map { "it:$it" } }
        val translator = server.translator()

        val first = translator.translate(listOf("Hi", "Hi", "123"), "it")
        val second = translator.translate(listOf("Hi"), "it")

        assertEquals(listOf("it:Hi", "it:Hi", "123"), first)
        assertEquals(listOf("it:Hi"), second)
        assertEquals(listOf(listOf("Hi")), server.batches)
    }

    @Test
    fun `a batch answer of the wrong size falls back to one paragraph at a time`() = runBlocking {
        val server = FakeTranslate { texts -> if (texts.size > 1) listOf("only one") else texts.map { "it:$it" } }

        val result = server.translator().translate(listOf("One", "Two"), "it")

        assertEquals(listOf("it:One", "it:Two"), result)
        assertEquals(listOf(listOf("One", "Two"), listOf("One"), listOf("Two")), server.batches)
    }

    @Test
    fun `a failed batch endpoint falls back to gtx`() = runBlocking {
        val server = FakeTranslate(batchFails = true) { texts -> texts }

        val result = server.translator().translate(listOf("Hello"), "it")

        assertEquals(listOf("gtx:Hello"), result)
    }

    /** Answers the two Google endpoints from [answer]; records the paragraphs of every batch call. */
    private class FakeTranslate(
        private val batchFails: Boolean = false,
        private val answer: (List<String>) -> List<String>,
    ) {
        val batches = mutableListOf<List<String>>()

        fun translator(): NovelTranslator {
            val client = OkHttpClient.Builder()
                .addInterceptor { chain -> respond(chain.request()) }
                .build()
            val network = mockk<NetworkHelper> { every { this@mockk.client } returns client }
            return NovelTranslator(network, Json)
        }

        private fun respond(request: Request): Response {
            val body = if (request.url.host == "clients5.google.com") {
                val form = request.body as FormBody
                val texts = (0 until form.size).map { form.value(it) }
                batches += texts
                if (batchFails) return response(request, 500, "")
                answer(texts).joinToString(",", "[", "]") { "[\"$it\",\"en\"]" }
            } else {
                val q = request.url.queryParameter("q")
                """[[["gtx:$q","$q"]]]"""
            }
            return response(request, 200, body)
        }

        private fun response(request: Request, code: Int, body: String) = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
