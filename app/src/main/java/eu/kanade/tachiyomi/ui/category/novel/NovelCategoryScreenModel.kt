package eu.kanade.tachiyomi.ui.category.novel

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.novel.repository.NovelCategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Unlike manga and anime, the novel layer has no interactors: the screen model talks to
 * [NovelCategoryRepository] directly, as the rest of the novel code does.
 */
class NovelCategoryScreenModel(
    private val categoryRepository: NovelCategoryRepository = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : StateScreenModel<NovelCategoryScreenState>(NovelCategoryScreenState.Loading) {

    private val _events: Channel<NovelCategoryEvent> = Channel()
    val events = _events.receiveAsFlow()

    private val reorderMutex = Mutex()

    init {
        screenModelScope.launch {
            val allCategories = if (libraryPreferences.hideHiddenCategoriesSettings().get()) {
                categoryRepository.getAllVisibleNovelCategoriesAsFlow()
            } else {
                categoryRepository.getAllNovelCategoriesAsFlow()
            }

            allCategories.collectLatest { categories ->
                mutableState.update {
                    NovelCategoryScreenState.Success(
                        categories = categories
                            .filterNot(Category::isSystemCategory)
                            .toImmutableList(),
                    )
                }
            }
        }
    }

    fun createCategory(name: String) {
        runOperation {
            val nextOrder = categoryRepository.getAllNovelCategories()
                .maxOfOrNull { it.order }
                ?.plus(1)
                ?: 0
            categoryRepository.insertNovelCategory(
                Category(id = 0, name = name, order = nextOrder, flags = 0, hidden = false),
            )
        }
    }

    fun renameCategory(category: Category, name: String) {
        runOperation {
            categoryRepository.updatePartialNovelCategory(CategoryUpdate(id = category.id, name = name))
        }
    }

    fun hideCategory(category: Category) {
        runOperation {
            categoryRepository.updatePartialNovelCategory(
                CategoryUpdate(id = category.id, hidden = !category.hidden),
            )
        }
    }

    fun deleteCategory(categoryId: Long) {
        runOperation {
            categoryRepository.deleteNovelCategory(categoryId)
            categoryRepository.updatePartialNovelCategories(orderUpdates(categoryRepository.getAllNovelCategories()))
        }
    }

    fun changeOrder(category: Category, newIndex: Int) {
        runOperation {
            reorderMutex.withLock {
                val categories = categoryRepository.getAllNovelCategories()
                    .filterNot(Category::isSystemCategory)
                    .toMutableList()

                val currentIndex = categories.indexOfFirst { it.id == category.id }
                if (currentIndex == -1) return@withLock

                categories.add(newIndex, categories.removeAt(currentIndex))
                categoryRepository.updatePartialNovelCategories(orderUpdates(categories))
            }
        }
    }

    private fun orderUpdates(categories: List<Category>): List<CategoryUpdate> {
        return categories.mapIndexed { index, category ->
            CategoryUpdate(id = category.id, order = index.toLong())
        }
    }

    private fun runOperation(block: suspend () -> Unit) {
        screenModelScope.launch {
            withNonCancellableContext {
                try {
                    block()
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    _events.send(NovelCategoryEvent.InternalError)
                }
            }
        }
    }

    fun showDialog(dialog: NovelCategoryDialog) {
        mutableState.update {
            when (it) {
                NovelCategoryScreenState.Loading -> it
                is NovelCategoryScreenState.Success -> it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update {
            when (it) {
                NovelCategoryScreenState.Loading -> it
                is NovelCategoryScreenState.Success -> it.copy(dialog = null)
            }
        }
    }
}

sealed interface NovelCategoryDialog {
    data object Create : NovelCategoryDialog
    data class Rename(val category: Category) : NovelCategoryDialog
    data class Delete(val category: Category) : NovelCategoryDialog
}

sealed interface NovelCategoryEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : NovelCategoryEvent
    data object InternalError : LocalizedMessage(MR.strings.internal_error)
}

sealed interface NovelCategoryScreenState {

    @Immutable
    data object Loading : NovelCategoryScreenState

    @Immutable
    data class Success(
        val categories: ImmutableList<Category>,
        val dialog: NovelCategoryDialog? = null,
    ) : NovelCategoryScreenState {

        val isEmpty: Boolean
            get() = categories.isEmpty()
    }
}
