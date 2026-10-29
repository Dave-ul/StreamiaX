package eu.kanade.tachiyomi.ui.novel.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Translates chapter paragraphs with Google Translate's public web endpoints.
 *
 * Batching follows Dartotsu (lib/Screen/Reader/Engine/NovelTranslate.dart) and QuickNovel
 * (util/translation/GoogleTranslateOnline.kt): paragraphs go in batches of a few thousand
 * characters, and a batch that fails is retried one paragraph at a time. The batch endpoint
 * takes one `q` per paragraph and answers in the same order, so nothing has to be split back.
 */
class NovelTranslator(
    private val network: NetworkHelper = Injekt.get(),
    private val json: Json = Injekt.get(),
) {

    private val cache = mutableMapOf<String, String>()

    suspend fun translate(paragraphs: List<String>, to: String): List<String> = withIOContext {
        val target = googleCode(to)
        val pending = paragraphs.distinct().filter { "$target\u0000$it" !in cache && it.any(Char::isLetter) }
        batches(pending).forEach { batch ->
            val translated = runCatching { translateBatch(batch, target) }
                .onFailure {
                    if (it is CancellationException) throw it
                    logcat(LogPriority.WARN, it) { "Translation batch failed, retrying one by one" }
                }
                .getOrNull()
                ?.takeIf { it.size == batch.size }
                ?: batch.map { translateSingle(it, target) }
            batch.zip(translated).forEach { (source, result) -> cache["$target\u0000$source"] = result }
        }
        paragraphs.map { cache["$target\u0000$it"] ?: it }
    }

    private suspend fun translateBatch(texts: List<String>, target: String): List<String> {
        val body = FormBody.Builder().apply { texts.forEach { add("q", it) } }.build()
        val url = BATCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client", "dict-chrome-ex")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", target)
            .build()
        val response = network.client.newCall(Request.Builder().url(url).post(body).build()).awaitSuccess()
        return parseBatch(json.parseToJsonElement(response.body.string()))
    }

    /** One paragraph, trying the batch endpoint then gtx, a few times with a growing pause. */
    private suspend fun translateSingle(text: String, target: String): String {
        var lastError: Throwable = IllegalStateException("Translation failed")
        for (attempt in 1..RETRIES) {
            for (endpoint in listOf(::translateOne, ::gtx)) {
                try {
                    return endpoint(text, target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                }
            }
            delay(RETRY_DELAY_MS * attempt)
        }
        throw lastError
    }

    private suspend fun translateOne(text: String, target: String): String =
        translateBatch(listOf(text), target).single()

    private suspend fun gtx(text: String, target: String): String {
        val url = GTX_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", target)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()
        val response = network.client.newCall(Request.Builder().url(url).build()).awaitSuccess()
        return parseGtx(json.parseToJsonElement(response.body.string()))
    }

    companion object {
        private const val BATCH_URL = "https://clients5.google.com/translate_a/t"
        private const val GTX_URL = "https://translate.googleapis.com/translate_a/single"
        private const val BATCH_CHARS = 4000
        private const val RETRIES = 3
        private const val RETRY_DELAY_MS = 400L

        /** Groups paragraphs into batches of at most [BATCH_CHARS] characters (a longer one goes alone). */
        internal fun batches(texts: List<String>): List<List<String>> {
            val result = mutableListOf<List<String>>()
            var current = mutableListOf<String>()
            var size = 0
            texts.forEach { text ->
                if (current.isNotEmpty() && size + text.length > BATCH_CHARS) {
                    result += current
                    current = mutableListOf()
                    size = 0
                }
                current += text
                size += text.length
            }
            if (current.isNotEmpty()) result += current
            return result
        }

        /**
         * `[["testo","en"], …]` with `sl=auto`, or `["testo", …]` with a fixed source language.
         * Throws when an item is missing or blank, so the batch is retried paragraph by paragraph.
         */
        internal fun parseBatch(element: JsonElement): List<String> =
            element.jsonArray.map { item ->
                val text = if (item is JsonArray) item.firstOrNull()?.textOrNull() else item.textOrNull()
                requireNotNull(text?.takeIf(String::isNotBlank)) { "Missing translation in batch answer" }
            }

        /** `[[["testo","text",…], …], …]`: the translated sentences are the first item of each row. */
        internal fun parseGtx(element: JsonElement): String =
            element.jsonArray.first().jsonArray
                .joinToString("") { row -> row.jsonArray.firstOrNull()?.textOrNull().orEmpty() }
                .also { require(it.isNotBlank()) { "Empty translation" } }

        /** The string content, or null for JSON null and non-strings. */
        private fun JsonElement.textOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

        internal fun googleCode(language: String): String = when (language.lowercase()) {
            "zh", "zh-hans", "zh-cn" -> "zh-CN"
            "zh-hant", "zh-tw" -> "zh-TW"
            "fil" -> "tl"
            "he" -> "iw"
            else -> language
        }
    }
}
