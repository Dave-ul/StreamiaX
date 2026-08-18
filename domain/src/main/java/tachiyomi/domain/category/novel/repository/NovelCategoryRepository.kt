package tachiyomi.domain.category.novel.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate

interface NovelCategoryRepository {

    suspend fun getAllNovelCategories(): List<Category>

    fun getAllNovelCategoriesAsFlow(): Flow<List<Category>>

    fun getAllVisibleNovelCategoriesAsFlow(): Flow<List<Category>>

    suspend fun getCategoriesByNovelId(novelId: Long): List<Category>

    suspend fun insertNovelCategory(category: Category)

    suspend fun updatePartialNovelCategory(update: CategoryUpdate)

    suspend fun updatePartialNovelCategories(updates: List<CategoryUpdate>)

    suspend fun deleteNovelCategory(categoryId: Long)
}
