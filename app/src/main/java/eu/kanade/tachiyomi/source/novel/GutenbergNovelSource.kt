package eu.kanade.tachiyomi.source.novel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.novelsource.model.NovelsPage
import eu.kanade.tachiyomi.novelsource.model.SNovel
import eu.kanade.tachiyomi.novelsource.model.SNovelChapter
import eu.kanade.tachiyomi.novelsource.online.NovelHttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Built-in novel source backed by the Gutendex API (Project Gutenberg).
 *
 * Demonstrates the [NovelHttpSource] pipeline end to end with a real, stable JSON API
 * of public-domain books. The [NovelSource] contract mirrors lnreader's plugin interface,
 * so additional lnreader-style scraping sources can be added the same way.
 *
 * Each book is presented as a single chapter (the full text); the chapter URL points at
 * the plain-text format and [getChapterText] returns its body.
 */
class GutenbergNovelSource : NovelHttpSource() {

    override val name = "Project Gutenberg"
    override val lang = "en"
    override val supportsLatest = false
    override val baseUrl = "https://gutendex.com"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun getPopularNovels(page: Int): NovelsPage = catalog(query = null, page = page)

    override suspend fun getSearchNovels(page: Int, query: String): NovelsPage = catalog(query = query, page = page)

    override suspend fun getLatestUpdates(page: Int): NovelsPage = NovelsPage(emptyList(), false)

    private suspend fun catalog(query: String?, page: Int): NovelsPage {
        val params = buildList {
            add("languages=en")
            add("page=$page")
            if (!query.isNullOrBlank()) add("search=${query.encode()}")
        }.joinToString("&")
        val body = GET("$baseUrl/books?$params", headers).await().body.string()
        val response = json.decodeFromString<BookListResponse>(body)
        return NovelsPage(
            novels = response.results.map { it.toSNovel() },
            hasNextPage = response.next != null,
        )
    }

    override suspend fun getNovelDetails(novel: SNovel): SNovel {
        val book = fetchBook(novel.url)
        return book.toSNovel().apply { initialized = true }
    }

    override suspend fun getChapterList(novel: SNovel): List<SNovelChapter> {
        val book = fetchBook(novel.url)
        val textUrl = book.textUrl() ?: return emptyList()
        return listOf(
            SNovelChapter.create().apply {
                url = textUrl
                name = "Full text"
                chapter_number = 1f
            },
        )
    }

    override suspend fun getChapterText(chapter: SNovelChapter): String {
        val raw = GET(chapter.url, headers).await().body.string()
        // Plain-text Gutenberg files: present as simple HTML paragraphs for the reader.
        return if (chapter.url.contains(".htm")) {
            raw
        } else {
            raw.split("\n\n").joinToString("\n") { para ->
                "<p>${para.trim().replace("\n", " ")}</p>"
            }
        }
    }

    private suspend fun fetchBook(id: String): Book {
        val body = GET("$baseUrl/books/$id", headers).await().body.string()
        return json.decodeFromString<Book>(body)
    }

    private fun Book.toSNovel(): SNovel = SNovel.create().apply {
        url = id.toString()
        title = this@toSNovel.title
        author = authors.firstOrNull()?.name
        description = summaries.firstOrNull()
        genre = subjects.take(8).joinToString(", ")
        thumbnail_url = formats["image/jpeg"]
        status = SNovel.COMPLETED
    }

    private fun Book.textUrl(): String? =
        formats["text/plain; charset=utf-8"]
            ?: formats["text/plain; charset=us-ascii"]
            ?: formats.entries.firstOrNull { it.key.startsWith("text/plain") }?.value
            ?: formats["text/html"]

    private fun String.encode(): String = replace(" ", "%20")

    companion object {
        const val ID = 6_900_000_000_000_000_101L
    }
}

@Serializable
private data class BookListResponse(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<Book> = emptyList(),
)

@Serializable
private data class Book(
    val id: Int,
    val title: String = "Untitled",
    val authors: List<Person> = emptyList(),
    val summaries: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val formats: Map<String, String> = emptyMap(),
)

@Serializable
private data class Person(
    val name: String = "",
    @SerialName("birth_year") val birthYear: Int? = null,
    @SerialName("death_year") val deathYear: Int? = null,
)
