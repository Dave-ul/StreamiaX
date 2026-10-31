package eu.kanade.tachiyomi.source.novel.js

import java.util.concurrent.atomic.AtomicReference

/**
 * Spots a plugin that ignores the page number: such plugins return the same novels for every
 * page, and since lnreader has no hasNextPage, a catalog would load them forever.
 *
 * Only the last page loaded is kept. Another listing loaded in between can only hide a repeat,
 * and a page loaded again (a retry, a refresh) is not one: only page n holding exactly what page
 * n - 1 of the same listing held counts.
 */
class RepeatedPageGuard {

    private data class Loaded(val listing: String, val page: Int, val urls: List<String>)

    private val last = AtomicReference<Loaded?>(null)

    /** True when page [page] of [listing] holds exactly the urls of its page [page] - 1, loaded right before. */
    fun isRepeat(listing: String, page: Int, urls: List<String>): Boolean {
        val previous = last.getAndSet(Loaded(listing, page, urls))
        return page > 1 && urls.isNotEmpty() && previous == Loaded(listing, page - 1, urls)
    }
}
