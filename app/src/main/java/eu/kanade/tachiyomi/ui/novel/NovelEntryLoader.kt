package eu.kanade.tachiyomi.ui.novel

import eu.kanade.tachiyomi.novelsource.model.SNovel
import eu.kanade.tachiyomi.source.novel.NovelSourceManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.entries.novel.model.Novel
import tachiyomi.domain.entries.novel.model.NovelUpdate
import tachiyomi.domain.entries.novel.repository.NovelRepository
import tachiyomi.domain.items.novelchapter.model.NovelChapter
import tachiyomi.domain.items.novelchapter.repository.NovelChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Loads a novel from its source into the database; shared by the detail and migration screens. */
class NovelEntryLoader(
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
    private val chapterRepository: NovelChapterRepository = Injekt.get(),
) {

    /**
     * Novels are persisted on first sight with `favorite = false`, the way Mihon treats manga, so
     * chapters always have a stable id for the reader to record progress against.
     */
    suspend fun getOrInsert(sourceId: Long, url: String, title: String): Novel = dbLock.withLock {
        novelRepository.getNovelByUrlAndSourceId(url, sourceId)?.let { return it }
        val toInsert = Novel.create().copy(
            source = sourceId,
            url = url,
            title = title,
            dateAdded = System.currentTimeMillis(),
        )
        val id = novelRepository.insertNovel(toInsert) ?: error("Could not insert novel")
        toInsert.copy(id = id)
    }

    /** Fetches details and chapters from the source, stores them and returns both. */
    suspend fun refresh(novel: Novel): Pair<SNovel, List<NovelChapter>> {
        val source = sourceManager.get(novel.source) ?: error("Source ${novel.source} not available")
        val details = source.getNovelDetails(
            SNovel.create().also {
                it.url = novel.url
                it.title = novel.title
            },
        )
        val saved = novelRepository.updateNovel(
            NovelUpdate(
                id = novel.id,
                author = details.author,
                description = details.description,
                genre = details.genre?.split(",")?.map(String::trim),
                status = details.status.toLong(),
                thumbnailUrl = details.thumbnail_url,
                initialized = true,
            ),
        )
        check(saved) { "Could not save the details of ${novel.title}" }

        val remote = source.getChapterList(details)
        // Only the database part is locked: the network calls above can take long.
        val chapters = dbLock.withLock {
            val known = chapterRepository.getChapterByNovelId(novel.id)
            val plan = planChapterSync(novel.id, novel.title, remote, known, System.currentTimeMillis())
            chapterRepository.updateFromRemote(plan.toRemove, plan.toAdd, plan.toUpdate)
            chapterRepository.getChapterByNovelId(novel.id)
        }
        return details to chapters.sortedBy { it.sourceOrder }
    }

    private companion object {
        /**
         * Loaders are created per screen, so this is shared: two screens refreshing the same novel
         * (detail and migration) would otherwise both insert the chapters the other one is adding.
         */
        val dbLock = Mutex()
    }
}
