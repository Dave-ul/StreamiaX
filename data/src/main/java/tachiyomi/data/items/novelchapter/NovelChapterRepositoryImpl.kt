package tachiyomi.data.items.novelchapter

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.handlers.novel.NovelDatabaseHandler
import tachiyomi.domain.items.novelchapter.model.NovelChapter
import tachiyomi.domain.items.novelchapter.model.NovelChapterUpdate
import tachiyomi.domain.items.novelchapter.repository.NovelChapterRepository

class NovelChapterRepositoryImpl(
    private val handler: NovelDatabaseHandler,
) : NovelChapterRepository {

    override suspend fun addAll(chapters: List<NovelChapter>): List<NovelChapter> {
        return try {
            handler.await(inTransaction = true) {
                chapters.map { chapter ->
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
                    val lastInsertId = novel_chaptersQueries.selectLastInsertedRowId().executeAsOne()
                    chapter.copy(id = lastInsertId)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
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
            chapterUpdates.forEach { chapterUpdate ->
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
        }
    }

    override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
        try {
            handler.await { novel_chaptersQueries.removeChaptersWithIds(chapterIds) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
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
