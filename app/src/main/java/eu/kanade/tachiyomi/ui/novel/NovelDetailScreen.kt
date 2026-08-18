package eu.kanade.tachiyomi.ui.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.novelsource.NovelSource
import eu.kanade.tachiyomi.novelsource.model.SNovel
import eu.kanade.tachiyomi.novelsource.model.SNovelChapter
import eu.kanade.tachiyomi.source.novel.NovelSourceManager
import eu.kanade.tachiyomi.ui.category.CategoriesTab
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.novel.repository.NovelCategoryRepository
import tachiyomi.domain.entries.novel.model.Novel
import tachiyomi.domain.entries.novel.model.NovelUpdate
import tachiyomi.domain.entries.novel.repository.NovelRepository
import tachiyomi.domain.items.novelchapter.model.NovelChapter
import tachiyomi.domain.items.novelchapter.repository.NovelChapterRepository
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelDetailScreen(
    private val sourceId: Long,
    private val novelUrl: String,
    private val novelTitle: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelDetailScreenModel(sourceId, novelUrl, novelTitle) }
        val state by screenModel.state.collectAsState()

        Scaffold(
            topBar = {
                AppBar(
                    title = novelTitle,
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            actions = persistentListOf<AppBar.AppBarAction>().builder()
                                .apply {
                                    add(
                                        AppBar.Action(
                                            title = if (state.favorite) {
                                                "Remove from library"
                                            } else {
                                                "Add to library"
                                            },
                                            icon = if (state.favorite) {
                                                Icons.Filled.Favorite
                                            } else {
                                                Icons.Outlined.FavoriteBorder
                                            },
                                            onClick = screenModel::toggleFavorite,
                                        ),
                                    )
                                    if (state.favorite) {
                                        add(
                                            AppBar.Action(
                                                title = "Set categories",
                                                icon = Icons.Outlined.Label,
                                                onClick = screenModel::showChangeCategoryDialog,
                                            ),
                                        )
                                    }
                                }
                                .build(),
                        )
                    },
                )
            },
        ) { contentPadding ->
            when {
                state.loading -> Box(
                    Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(
                    Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Error: ${state.error}")
                }
                else -> LazyColumn(modifier = Modifier.padding(contentPadding)) {
                    if (!state.description.isNullOrBlank()) {
                        item {
                            Text(
                                state.description!!,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            HorizontalDivider()
                        }
                    }
                    items(state.chapters, key = { it.id }) { chapter ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navigator.push(NovelReaderScreen(chapter.id)) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = chapter.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (chapter.read) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        state.categoryDialogSelection?.let { selection ->
            ChangeCategoryDialog(
                initialSelection = selection,
                onDismissRequest = screenModel::dismissCategoryDialog,
                onEditCategories = {
                    navigator.push(CategoriesTab)
                    CategoriesTab.showNovelCategory()
                },
                onConfirm = { include, _ -> screenModel.setCategories(include) },
            )
        }
    }
}

class NovelDetailScreenModel(
    private val sourceId: Long,
    private val novelUrl: String,
    private val novelTitle: String,
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
    private val chapterRepository: NovelChapterRepository = Injekt.get(),
    private val categoryRepository: NovelCategoryRepository = Injekt.get(),
) : ScreenModel {

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var novelId: Long = -1

    init {
        load()
    }

    /**
     * Novels are persisted on first open with `favorite = false`, the way Mihon treats manga, so
     * chapters always have a stable id for the reader to record progress against.
     */
    private fun load() {
        screenModelScope.launch {
            runCatching {
                val source = sourceManager.get(sourceId) ?: error("Source $sourceId not available")
                val novel = getOrInsertNovel()
                novelId = novel.id

                val details = source.getNovelDetails(novel.toSNovel())
                novelRepository.updateNovel(
                    NovelUpdate(
                        id = novelId,
                        author = details.author,
                        description = details.description,
                        genre = details.genre?.split(",")?.map(String::trim),
                        status = details.status.toLong(),
                        thumbnailUrl = details.thumbnail_url,
                        initialized = true,
                    ),
                )

                val chapters = syncChapters(source, details)
                details.description to chapters
            }.onSuccess { (description, chapters) ->
                _state.update {
                    it.copy(loading = false, description = description, chapters = chapters)
                }
                observeFavorite()
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "unknown") }
            }
        }
    }

    private suspend fun getOrInsertNovel(): Novel {
        novelRepository.getNovelByUrlAndSourceId(novelUrl, sourceId)?.let { return it }
        val toInsert = Novel.create().copy(
            source = sourceId,
            url = novelUrl,
            title = novelTitle,
            dateAdded = System.currentTimeMillis(),
        )
        val id = novelRepository.insertNovel(toInsert) ?: error("Could not insert novel")
        return toInsert.copy(id = id)
    }

    /** Adds chapters that are new to the database; existing ones keep their read state. */
    private suspend fun syncChapters(source: NovelSource, details: SNovel): List<NovelChapter> {
        val remote = source.getChapterList(details)
        val known = chapterRepository.getChapterByNovelId(novelId).associateBy { it.url }
        val new = remote.filterNot { known.containsKey(it.url) }
            .mapIndexed { index, chapter -> chapter.toNovelChapter(novelId, known.size + index) }
        if (new.isNotEmpty()) chapterRepository.addAll(new)
        return chapterRepository.getChapterByNovelId(novelId).sortedBy { it.sourceOrder }
    }

    private fun observeFavorite() {
        screenModelScope.launch {
            novelRepository.getNovelByIdAsFlow(novelId).collect { novel ->
                _state.update { it.copy(favorite = novel.favorite) }
            }
        }
    }

    fun toggleFavorite() {
        if (novelId == -1L) return
        screenModelScope.launch {
            if (_state.value.favorite) {
                novelRepository.updateNovel(NovelUpdate(id = novelId, favorite = false))
                novelRepository.setNovelCategories(novelId, emptyList())
                return@launch
            }
            // Adding to the library: let the user pick categories first, as Mihon does.
            if (userCategories().isEmpty()) {
                novelRepository.updateNovel(NovelUpdate(id = novelId, favorite = true))
            } else {
                showChangeCategoryDialog()
            }
        }
    }

    fun showChangeCategoryDialog() {
        if (novelId == -1L) return
        screenModelScope.launch {
            val categories = userCategories()
            val selected = categoryRepository.getCategoriesByNovelId(novelId).map { it.id }
            _state.update {
                it.copy(
                    categoryDialogSelection = categories
                        .mapAsCheckboxState { category -> category.id in selected }
                        .toImmutableList(),
                )
            }
        }
    }

    fun dismissCategoryDialog() {
        _state.update { it.copy(categoryDialogSelection = null) }
    }

    fun setCategories(categoryIds: List<Long>) {
        if (novelId == -1L) return
        screenModelScope.launch {
            novelRepository.setNovelCategories(novelId, categoryIds)
            if (!_state.value.favorite) {
                novelRepository.updateNovel(NovelUpdate(id = novelId, favorite = true))
            }
            dismissCategoryDialog()
        }
    }

    private suspend fun userCategories(): List<Category> =
        categoryRepository.getAllNovelCategories().filterNot(Category::isSystemCategory)

    private fun Novel.toSNovel(): SNovel = SNovel.create().also {
        it.url = url
        it.title = title
    }

    private fun SNovelChapter.toNovelChapter(novelId: Long, order: Int): NovelChapter =
        NovelChapter.create().copy(
            novelId = novelId,
            url = url,
            name = name,
            dateUpload = date_upload,
            chapterNumber = chapter_number.toDouble(),
            sourceOrder = order.toLong(),
            dateFetch = System.currentTimeMillis(),
        )

    data class State(
        val loading: Boolean = true,
        val favorite: Boolean = false,
        val description: String? = null,
        val chapters: List<NovelChapter> = emptyList(),
        val categoryDialogSelection: ImmutableList<CheckboxState<Category>>? = null,
        val error: String? = null,
    )
}
