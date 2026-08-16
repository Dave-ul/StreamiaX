package tachiyomi.data.entries.novel

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.handlers.novel.NovelDatabaseHandler
import tachiyomi.domain.entries.novel.model.Novel
import tachiyomi.domain.entries.novel.model.NovelUpdate
import tachiyomi.domain.entries.novel.repository.NovelRepository
import tachiyomi.domain.library.novel.LibraryNovel

class NovelRepositoryImpl(
    private val handler: NovelDatabaseHandler,
) : NovelRepository {

    override suspend fun getNovelById(id: Long): Novel {
        return handler.awaitOne { novelsQueries.getNovelById(id, NovelMapper::mapNovel) }
    }

    override suspend fun getNovelByIdAsFlow(id: Long): Flow<Novel> {
        return handler.subscribeToOne { novelsQueries.getNovelById(id, NovelMapper::mapNovel) }
    }

    override suspend fun getNovelByUrlAndSourceId(url: String, sourceId: Long): Novel? {
        return handler.awaitOneOrNull {
            novelsQueries.getNovelByUrlAndSource(url, sourceId, NovelMapper::mapNovel)
        }
    }

    override fun getNovelByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Novel?> {
        return handler.subscribeToOneOrNull {
            novelsQueries.getNovelByUrlAndSource(url, sourceId, NovelMapper::mapNovel)
        }
    }

    override suspend fun getNovelFavorites(): List<Novel> {
        return handler.awaitList { novelsQueries.getFavorites(NovelMapper::mapNovel) }
    }

    override suspend fun getLibraryNovels(): List<LibraryNovel> {
        return handler.awaitList { novellibraryViewQueries.novellibrary(NovelMapper::mapLibraryNovel) }
    }

    override fun getLibraryNovelsAsFlow(): Flow<List<LibraryNovel>> {
        return handler.subscribeToList { novellibraryViewQueries.novellibrary(NovelMapper::mapLibraryNovel) }
    }

    override fun getNovelFavoritesBySourceId(sourceId: Long): Flow<List<Novel>> {
        return handler.subscribeToList { novelsQueries.getFavoriteBySourceId(sourceId, NovelMapper::mapNovel) }
    }

    override suspend fun getDuplicateLibraryNovel(id: Long, title: String): List<Novel> {
        return handler.awaitList {
            novelsQueries.getDuplicateLibraryNovel(title, id, NovelMapper::mapNovel)
        }
    }

    override suspend fun resetNovelReaderFlags(): Boolean {
        return try {
            handler.await { novelsQueries.resetReaderFlags() }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun setNovelCategories(novelId: Long, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            novels_categoriesQueries.deleteNovelCategoryByNovelId(novelId)
            categoryIds.forEach { categoryId ->
                novels_categoriesQueries.insert(novelId, categoryId)
            }
        }
    }

    override suspend fun insertNovel(novel: Novel): Long? {
        return handler.await(inTransaction = true) {
            novelsQueries.insert(
                source = novel.source,
                url = novel.url,
                author = novel.author,
                description = novel.description,
                genre = novel.genre,
                title = novel.title,
                status = novel.status,
                thumbnailUrl = novel.thumbnailUrl,
                favorite = novel.favorite,
                lastUpdate = novel.lastUpdate,
                nextUpdate = novel.nextUpdate,
                initialized = novel.initialized,
                readerFlags = novel.readerFlags,
                chapterFlags = novel.chapterFlags,
                coverLastModified = novel.coverLastModified,
                dateAdded = novel.dateAdded,
                updateStrategy = novel.updateStrategy,
                calculateInterval = novel.fetchInterval.toLong(),
            )
            novelsQueries.selectLastInsertedRowId().executeAsOneOrNull()
        }
    }

    override suspend fun updateNovel(update: NovelUpdate): Boolean {
        return try {
            partialUpdateNovel(update)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun updateAllNovels(novelUpdates: List<NovelUpdate>): Boolean {
        return try {
            partialUpdateNovel(*novelUpdates.toTypedArray())
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    private suspend fun partialUpdateNovel(vararg novelUpdates: NovelUpdate) {
        handler.await(inTransaction = true) {
            novelUpdates.forEach { value ->
                novelsQueries.update(
                    source = value.source,
                    url = value.url,
                    author = value.author,
                    description = value.description,
                    genre = value.genre?.let(StringListColumnAdapter::encode),
                    title = value.title,
                    status = value.status,
                    thumbnailUrl = value.thumbnailUrl,
                    favorite = value.favorite,
                    lastUpdate = value.lastUpdate,
                    nextUpdate = value.nextUpdate,
                    initialized = value.initialized,
                    readerFlags = value.readerFlags,
                    chapterFlags = value.chapterFlags,
                    coverLastModified = value.coverLastModified,
                    dateAdded = value.dateAdded,
                    // Same enum, same ordinal encoding as the manga column adapter
                    updateStrategy = value.updateStrategy?.let(MangaUpdateStrategyColumnAdapter::encode),
                    calculateInterval = value.fetchInterval?.toLong(),
                    novelId = value.id,
                )
            }
        }
    }
}
