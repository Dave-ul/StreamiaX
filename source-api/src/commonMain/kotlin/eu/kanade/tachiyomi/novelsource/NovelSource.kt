package eu.kanade.tachiyomi.novelsource

import eu.kanade.tachiyomi.novelsource.model.NovelsPage
import eu.kanade.tachiyomi.novelsource.model.SNovel
import eu.kanade.tachiyomi.novelsource.model.SNovelChapter

/**
 * A source of light novels / web novels. The third content type alongside
 * [eu.kanade.tachiyomi.animesource.AnimeSource] and the manga `Source`.
 *
 * Chapters resolve to text (HTML or plain), not images or video, so [getChapterText]
 * returns the chapter body as a string consumed by the novel text reader.
 */
interface NovelSource {

    /** Unique ID for the source. */
    val id: Long

    /** Name of the source. */
    val name: String

    /** An ISO 639-1 language code (two lower-case letters), or "all". */
    val lang: String
        get() = ""

    /** Whether the source supports latest updates. */
    val supportsLatest: Boolean

    suspend fun getPopularNovels(page: Int): NovelsPage

    suspend fun getSearchNovels(page: Int, query: String): NovelsPage

    suspend fun getLatestUpdates(page: Int): NovelsPage

    suspend fun getNovelDetails(novel: SNovel): SNovel

    suspend fun getChapterList(novel: SNovel): List<SNovelChapter>

    /**
     * Returns the chapter body as HTML or plain text.
     */
    suspend fun getChapterText(chapter: SNovelChapter): String
}
