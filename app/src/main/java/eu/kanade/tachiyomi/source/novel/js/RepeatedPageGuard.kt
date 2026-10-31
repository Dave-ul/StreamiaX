package eu.kanade.tachiyomi.source.novel.js

import java.util.concurrent.atomic.AtomicReference

/**
 * Spots a plugin that ignores the page number: such plugins return the same novels for every
 * page, and since lnreader has no hasNextPage, a catalog would load them forever.
 *
 * Only the last page loaded is kept. Another listing loaded in between can only hide a repeat,
 * never report a false one.
 */
class RepeatedPageGuard {

    private val last = AtomicReference<Pair<String, List<String>>?>(null)

    /** True when page [page] of [listing] holds exactly the urls of the page loaded right before it. */
    fun isRepeat(listing: String, page: Int, urls: List<String>): Boolean {
        val previous = last.getAndSet(listing to urls)
        return page > 1 && urls.isNotEmpty() && previous == (listing to urls)
    }
}
