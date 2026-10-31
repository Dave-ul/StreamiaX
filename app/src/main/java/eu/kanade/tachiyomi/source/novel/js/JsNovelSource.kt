package eu.kanade.tachiyomi.source.novel.js

import eu.kanade.tachiyomi.novelsource.model.NovelsPage
import eu.kanade.tachiyomi.novelsource.model.SNovel
import eu.kanade.tachiyomi.novelsource.model.SNovelChapter
import eu.kanade.tachiyomi.novelsource.online.NovelHttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * A [eu.kanade.tachiyomi.novelsource.NovelSource] backed by an installed lnreader JS plugin.
 *
 * Result mapping (parsePage pagination, status strings, release dates) follows mangayomi's
 * lnreader service (lib/eval/lnreader/service.dart, Apache-2.0). Unlike mangayomi, chapters keep
 * lnreader's oldest-first order, which is the source order the novel screens already expect.
 */
class JsNovelSource(
    val plugin: LnReaderPlugin,
    private val codeFile: File,
) : NovelHttpSource() {

    override val name = plugin.name
    override val lang = plugin.lang
    override val baseUrl = plugin.site.trimEnd('/')
    override val supportsLatest = true
    override val id by lazy { generateId(plugin.id, "lnreader", 1) }

    private val thread = JsPluginThread(plugin.id, JS_TIMEOUT_MS, IDLE_MS) {
        JsNovelRuntime(plugin.id, codeFile.readText())
    }

    @Volatile
    private var lastNovel: SourceNovel? = null

    private val pages = RepeatedPageGuard()

    private suspend fun <T> js(block: (JsNovelRuntime) -> T): T = thread.run(block)

    /** Frees the plugin thread and its QuickJS instance, e.g. after the plugin is updated or uninstalled. */
    fun close() {
        thread.close()
        lastNovel = null
    }

    override suspend fun getPopularNovels(page: Int) = novels(page, latest = false)

    override suspend fun getLatestUpdates(page: Int) = novels(page, latest = true)

    override suspend fun getSearchNovels(page: Int, query: String): NovelsPage {
        val items = js { it.call("searchNovels", JsonPrimitive(query), JsonPrimitive(page)) }
        return toPage("search:$query", page, items)
    }

    private suspend fun novels(page: Int, latest: Boolean): NovelsPage {
        val options = buildJsonObject { put("showLatestNovels", latest) }
        return toPage("popular:$latest", page, js { it.call("popularNovels", JsonPrimitive(page), options) })
    }

    /** lnreader has no hasNextPage, so any non-empty page that is not a repeat means "try the next one". */
    private fun toPage(listing: String, page: Int, items: JsonElement): NovelsPage {
        val novels = JSON.decodeFromJsonElement<List<NovelItem>>(items).map { item ->
            SNovel.create().apply {
                url = item.path
                title = item.name
                thumbnail_url = item.cover
            }
        }
        if (pages.isRepeat(listing, page, novels.map { it.url })) return NovelsPage(emptyList(), hasNextPage = false)
        return NovelsPage(novels, hasNextPage = novels.isNotEmpty())
    }

    override suspend fun getNovelDetails(novel: SNovel): SNovel {
        val parsed = parseNovel(novel.url).also { lastNovel = it }
        return SNovel.create().apply {
            url = novel.url
            title = parsed.name.ifBlank { novel.title }
            author = parsed.author
            artist = parsed.artist
            description = parsed.summary
            genre = parsed.genres
            status = parsed.status.toSNovelStatus()
            thumbnail_url = parsed.cover
            initialized = true
        }
    }

    /** Reuses the parseNovel result of the [getNovelDetails] call that normally comes right before. */
    override suspend fun getChapterList(novel: SNovel): List<SNovelChapter> {
        val parsed = lastNovel?.takeIf { it.path == novel.url }.also { lastNovel = null } ?: parseNovel(novel.url)
        return parsed.chapters.map { chapter ->
            SNovelChapter.create().apply {
                url = chapter.path
                name = chapter.name
                date_upload = parseDate(chapter.releaseTime)
                chapter_number = chapter.chapterNumber ?: -1f
            }
        }
    }

    override suspend fun getChapterText(chapter: SNovelChapter): String =
        (js { it.call("parseChapter", JsonPrimitive(chapter.url)) } as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** One JS call per page, so each gets the whole timeout. */
    private suspend fun parseNovel(path: String): SourceNovel {
        val novel = JSON.decodeFromJsonElement<SourceNovel>(js { it.call("parseNovel", JsonPrimitive(path)) })
            .let { it.copy(path = it.path.ifBlank { path }) }
        val totalPages = novel.totalPages
        if (novel.chapters.isNotEmpty() && (totalPages == null || totalPages <= 1)) return novel

        val chapters = novel.chapters.toMutableList()
        var page = if (chapters.isEmpty()) 1 else 2
        val maxPages = totalPages ?: MAX_GUESSED_PAGES
        while (page <= maxPages) {
            val got = try {
                JSON.decodeFromJsonElement<SourcePage>(
                    js { it.call("parsePage", JsonPrimitive(novel.path), JsonPrimitive(page.toString())) },
                ).chapters
            } catch (e: CancellationException) {
                throw e
            } catch (e: JsTimeoutException) {
                // A slow site, not the end of the list
                throw e
            } catch (e: Exception) {
                // With a known page count a failed page fails the whole list: a list cut short would
                // make the sync delete the chapters after it, with their read state and history.
                // When guessing, many plugins throw past the last page, so that ends the list.
                if (totalPages != null || page == 1) throw e
                logcat(LogPriority.WARN, e) { "${plugin.name}: stopped guessing pages at $page" }
                break
            }
            if (got.isEmpty()) break
            chapters += got
            // Without a page count, a short page is taken to be the last one
            if (totalPages == null && got.size < MIN_FULL_PAGE) break
            page++
        }
        return novel.copy(chapters = chapters)
    }

    @Serializable
    data class NovelItem(val name: String = "", val path: String, val cover: String? = null)

    @Serializable
    data class ChapterItem(
        val name: String = "",
        val path: String,
        val releaseTime: String? = null,
        val chapterNumber: Float? = null,
    )

    @Serializable
    data class SourceNovel(
        val name: String = "",
        val path: String = "",
        val cover: String? = null,
        val genres: String? = null,
        val summary: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val status: String? = null,
        val chapters: List<ChapterItem> = emptyList(),
        val totalPages: Int? = null,
    )

    @Serializable
    data class SourcePage(val chapters: List<ChapterItem> = emptyList())

    private companion object {
        const val MAX_GUESSED_PAGES = 50
        const val MIN_FULL_PAGE = 5

        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        /** A few seconds over the runtime's own limit, which normally fails the call first. */
        const val JS_TIMEOUT_MS = 65_000L

        /** A runtime holds the parsed libs.js; one unused for this long is freed. */
        const val IDLE_MS = 5 * 60_000L

        fun String?.toSNovelStatus() = when (this) {
            "Ongoing" -> SNovel.ONGOING
            "Completed" -> SNovel.COMPLETED
            "Publishing Finished" -> SNovel.PUBLISHING_FINISHED
            "Licensed" -> SNovel.LICENSED
            "Cancelled" -> SNovel.CANCELLED
            "On Hiatus" -> SNovel.ON_HIATUS
            else -> SNovel.UNKNOWN
        }

        /** ISO dates or epoch millis; free-form dates ("2 days ago", "LL" formats) become 0. */
        fun parseDate(value: String?): Long {
            if (value.isNullOrBlank()) return 0L
            value.toLongOrNull()?.let { return it }
            val zone = ZoneId.systemDefault()
            return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                .recoverCatching { Instant.parse(value).toEpochMilli() }
                .recoverCatching { LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli() }
                .recoverCatching { LocalDate.parse(value).atStartOfDay(zone).toInstant().toEpochMilli() }
                .getOrDefault(0L)
        }
    }
}
