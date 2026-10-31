package eu.kanade.tachiyomi.source.novel.js

import kotlinx.coroutines.CancellationException
import java.io.IOException

/** One parsePage call: its chapters, and the network failure the plugin may have hidden. */
internal class LoadedPage<T>(val chapters: List<T>, val networkError: IOException? = null)

/**
 * Collects the chapters of a novel whose list is split over pages: [first] came with parseNovel
 * and [loadPage] loads one more page.
 *
 * With a known [totalPages] a failed page fails the whole list: a list cut short would make the
 * sync delete the chapters after it, with their read state and history. Without it pages are
 * guessed until an empty or short page, or a plugin error, since many plugins throw past the last
 * page; [onGuessEnd] is told about that error. A timeout or a network failure is never the end of
 * the list, also when the plugin turned it into an empty page.
 */
internal suspend fun <T> collectChapterPages(
    first: List<T>,
    totalPages: Int?,
    onGuessEnd: (page: Int, error: Exception) -> Unit = { _, _ -> },
    loadPage: suspend (page: Int) -> LoadedPage<T>,
): List<T> {
    val chapters = first.toMutableList()
    var page = if (chapters.isEmpty()) 1 else 2
    val maxPages = totalPages ?: MAX_GUESSED_PAGES
    while (page <= maxPages) {
        val loaded = try {
            loadPage(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: JsTimeoutException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            if (totalPages != null || page == 1) throw e
            onGuessEnd(page, e)
            break
        }
        val got = loaded.chapters
        loaded.networkError?.let { if (totalPages == null || got.isEmpty()) throw it }
        if (got.isEmpty()) break
        chapters += got
        // Without a page count, a short page is taken to be the last one
        if (totalPages == null && got.size < MIN_FULL_PAGE) break
        page++
    }
    return chapters
}

private const val MAX_GUESSED_PAGES = 50
private const val MIN_FULL_PAGE = 5
