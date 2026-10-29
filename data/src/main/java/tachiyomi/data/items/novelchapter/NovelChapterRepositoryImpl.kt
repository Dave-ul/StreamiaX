package tachiyomi.data.items.novelchapter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.handlers.novel.NovelDatabaseHandler
import tachiyomi.domain.items.novelchapter.model.NovelChapter
import tachiyomi.domain.items.novelchapter.model.NovelChapterUpdate
import tachiyomi.domain.items.novelchapter.model.NovelHistoryItem
import tachiyomi.domain.items.novelchapter.repository.NovelChapterRepository
import tachiyomi.novel.data.NovelDatabase
import java.util.Date

class NovelChapterRepositoryImpl(
    private val handler: NovelDatabaseHandler,
) : NovelChapterRepository {

    // Errors are not caught here: a sync that fails must reach the screen, not look like "no chapters".
    override suspend fun updateFromRemote(
        removedIds: List<Long>,
        added: List<NovelChapter>,
        updated: List<NovelChapterUpdate>,
    ): List<NovelChapter> {
        return handler.await(inTransaction = true) {
            if (removedIds.isNotEmpty()) novel_chaptersQueries.removeChaptersWithIds(removedIds)
            val existing = added.map { it.novelId }
                .distinct()
                .flatMap { novelId ->
                    novel_chaptersQueries.getChaptersByNovelId(novelId).executeAsList().map { novelId to it.url }
                }
                .toMutableSet()
            val stored = added.filter { existing.add(it.novelId to it.url) }.map { chapter ->
                novel_chaptersQueries.insert(
                    novelId = chapter.novelId,
                    url = chapter.url,
                    name = chapter.name,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPosition = chapter.lastPosition,
                    chapterNumber = chapter.chapterNumber,
                    sourceOrder = chapter.sourceOrder,
                    dateFetch = chapter.dateFetch,
                    dateUpload = chapter.dateUpload,
                )
                chapter.copy(id = novel_chaptersQueries.selectLastInsertedRowId().executeAsOne())
            }
            updated.forEach { updateChapter(it) }
            stored
        }
    }

    override suspend fun update(chapterUpdate: NovelChapterUpdate) {
        partialUpdate(chapterUpdate)
    }

    override suspend fun updateAll(chapterUpdates: List<NovelChapterUpdate>) {
        partialUpdate(*chapterUpdates.toTypedArray())
    }

    private suspend fun partialUpdate(vararg chapterUpdates: NovelChapterUpdate) {
        handler.await(inTransaction = true) {
            chapterUpdates.forEach { updateChapter(it) }
        }
    }

    private fun NovelDatabase.updateChapter(chapterUpdate: NovelChapterUpdate) {
        novel_chaptersQueries.update(
            novelId = chapterUpdate.novelId,
            url = chapterUpdate.url,
            name = chapterUpdate.name,
            read = chapterUpdate.read,
            bookmark = chapterUpdate.bookmark,
            lastPosition = chapterUpdate.lastPosition,
            chapterNumber = chapterUpdate.chapterNumber,
            sourceOrder = chapterUpdate.sourceOrder,
            dateFetch = chapterUpdate.dateFetch,
            dateUpload = chapterUpdate.dateUpload,
            chapterId = chapterUpdate.id,
        )
    }

    override suspend fun getChapterByNovelId(novelId: Long): List<NovelChapter> {
        return handler.awaitList { novel_chaptersQueries.getChaptersByNovelId(novelId, ::mapChapter) }
    }

    override suspend fun getBookmarkedChaptersByNovelId(novelId: Long): List<NovelChapter> {
        return handler.awaitList {
            novel_chaptersQueries.getBookmarkedChaptersByNovelId(novelId, ::mapChapter)
        }
    }

    override suspend fun getChapterById(id: Long): NovelChapter? {
        return handler.awaitOneOrNull { novel_chaptersQueries.getChapterById(id, ::mapChapter) }
    }

    override suspend fun getChapterByNovelIdAsFlow(novelId: Long): Flow<List<NovelChapter>> {
        return handler.subscribeToList { novel_chaptersQueries.getChaptersByNovelId(novelId, ::mapChapter) }
    }

    override suspend fun getChapterByUrlAndNovelId(url: String, novelId: Long): NovelChapter? {
        return handler.awaitOneOrNull {
            novel_chaptersQueries.getChapterByUrlAndNovelId(url, novelId, ::mapChapter)
        }
    }

    override suspend fun upsertHistory(chapterId: Long, readAt: Date, sessionReadDuration: Long) {
        handler.await { novelhistoryQueries.upsert(chapterId, readAt, sessionReadDuration) }
    }

    override fun getRecentHistoryAsFlow(): Flow<List<NovelHistoryItem>> {
        return handler.subscribeToList { novelhistoryQueries.getRecent() }.map { rows ->
            rows.map {
                NovelHistoryItem(
                    novelId = it.novelId,
                    chapterId = it.chapterId,
                    title = it.title,
                    thumbnailUrl = it.thumbnail_url,
                    source = it.source,
                    novelUrl = it.novelUrl,
                    chapterName = it.chapterName,
                    readAt = it.last_read,
                )
            }
        }
    }

    private fun mapChapter(
        id: Long,
        novelId: Long,
        url: String,
        name: String,
        read: Boolean,
        bookmark: Boolean,
        lastPosition: Long,
        chapterNumber: Double,
        sourceOrder: Long,
        dateFetch: Long,
        dateUpload: Long,
    ): NovelChapter = NovelChapter(
        id = id,
        novelId = novelId,
        read = read,
        bookmark = bookmark,
        lastPosition = lastPosition,
        dateFetch = dateFetch,
        sourceOrder = sourceOrder,
        url = url,
        name = name,
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
    )
}
