package tachiyomi.domain.items.novelchapter.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.items.novelchapter.model.NovelChapter
import tachiyomi.domain.items.novelchapter.model.NovelChapterRemoteUpdate
import tachiyomi.domain.items.novelchapter.model.NovelChapterUpdate
import tachiyomi.domain.items.novelchapter.model.NovelHistoryItem
import java.util.Date

interface NovelChapterRepository {

    /**
     * Applies a sync with the source in one transaction, as Mihon's `ChapterRepository.updateFromRemote`:
     * either the whole chapter list changes or nothing does. Chapters in [added] whose url the novel
     * already has, or that repeat a url within [added], are skipped. Returns the inserted chapters
     * with their ids.
     *
     * Throws when the transaction fails; nothing is written then.
     */
    suspend fun updateFromRemote(
        removedIds: List<Long>,
        added: List<NovelChapter>,
        updated: List<NovelChapterRemoteUpdate>,
    ): List<NovelChapter>

    suspend fun update(chapterUpdate: NovelChapterUpdate)

    suspend fun updateAll(chapterUpdates: List<NovelChapterUpdate>)

    suspend fun getChapterByNovelId(novelId: Long): List<NovelChapter>

    suspend fun getBookmarkedChaptersByNovelId(novelId: Long): List<NovelChapter>

    suspend fun getChapterById(id: Long): NovelChapter?

    suspend fun getChapterByNovelIdAsFlow(novelId: Long): Flow<List<NovelChapter>>

    suspend fun getChapterByUrlAndNovelId(url: String, novelId: Long): NovelChapter?

    /**
     * Records a reading session against [chapterId].
     *
     * Novel history lives here rather than behind its own repository because it is one upsert and
     * one list; split it out once it grows (delete, search). Throws when the write fails.
     */
    suspend fun upsertHistory(chapterId: Long, readAt: Date, sessionReadDuration: Long)

    fun getRecentHistoryAsFlow(): Flow<List<NovelHistoryItem>>
}
