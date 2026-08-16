package tachiyomi.domain.entries.novel.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.entries.novel.model.Novel
import tachiyomi.domain.entries.novel.model.NovelUpdate
import tachiyomi.domain.library.novel.LibraryNovel

interface NovelRepository {

    suspend fun getNovelById(id: Long): Novel

    suspend fun getNovelByIdAsFlow(id: Long): Flow<Novel>

    suspend fun getNovelByUrlAndSourceId(url: String, sourceId: Long): Novel?

    fun getNovelByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Novel?>

    suspend fun getNovelFavorites(): List<Novel>

    suspend fun getLibraryNovels(): List<LibraryNovel>

    fun getLibraryNovelsAsFlow(): Flow<List<LibraryNovel>>

    fun getNovelFavoritesBySourceId(sourceId: Long): Flow<List<Novel>>

    suspend fun getDuplicateLibraryNovel(id: Long, title: String): List<Novel>

    suspend fun resetNovelReaderFlags(): Boolean

    suspend fun setNovelCategories(novelId: Long, categoryIds: List<Long>)

    suspend fun insertNovel(novel: Novel): Long?

    suspend fun updateNovel(update: NovelUpdate): Boolean

    suspend fun updateAllNovels(novelUpdates: List<NovelUpdate>): Boolean
}
