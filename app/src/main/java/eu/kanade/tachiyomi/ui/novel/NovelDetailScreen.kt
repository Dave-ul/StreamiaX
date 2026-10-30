package eu.kanade.tachiyomi.ui.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.novelsource.model.SNovel
import eu.kanade.tachiyomi.ui.category.CategoriesTab
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.novel.repository.NovelCategoryRepository
import tachiyomi.domain.entries.novel.model.NovelUpdate
import tachiyomi.domain.entries.novel.repository.NovelRepository
import tachiyomi.domain.items.novelchapter.model.NovelChapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
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
        val context = LocalContext.current
        LaunchedEffect(screenModel) {
            screenModel.writeErrors.collect { context.toast(it) }
        }

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
                                            title = stringResource(
                                                if (state.favorite) {
                                                    MR.strings.remove_from_library
                                                } else {
                                                    MR.strings.add_to_library
                                                },
                                            ),
                                            icon = if (state.favorite) {
                                                Icons.Filled.Favorite
                                            } else {
                                                Icons.Outlined.FavoriteBorder
                                            },
                                            onClick = screenModel::toggleFavorite,
                                        ),
                                    )
                                    add(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_webview_refresh),
                                            icon = Icons.Outlined.Refresh,
                                            onClick = screenModel::refresh,
                                        ),
                                    )
                                    if (state.favorite) {
                                        add(
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_move_category),
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
                    item {
                        NovelHeader(
                            title = novelTitle,
                            info = state.info,
                            resumeChapter = state.chapters.firstOrNull { !it.read },
                            started = state.chapters.any { it.read },
                            onResume = { navigator.push(NovelReaderScreen(it.id)) },
                        )
                        HorizontalDivider()
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

data class NovelInfo(
    val cover: String?,
    val author: String?,
    val status: Int,
    val genres: String?,
    val description: String?,
)

@Composable
private fun NovelHeader(
    title: String,
    info: NovelInfo?,
    resumeChapter: NovelChapter?,
    started: Boolean,
    onResume: (NovelChapter) -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Row {
            ItemCover.Book(data = info?.cover, modifier = Modifier.width(100.dp))
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    info?.author?.takeIf { it.isNotBlank() } ?: stringResource(MR.strings.unknown_author),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(novelStatusText(info?.status ?: SNovel.UNKNOWN)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (resumeChapter != null) {
                    Button(onClick = { onResume(resumeChapter) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(if (started) MR.strings.action_resume else MR.strings.action_start))
                    }
                }
            }
        }
        info?.genres?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        info?.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun novelStatusText(status: Int) = when (status) {
    SNovel.ONGOING -> MR.strings.ongoing
    SNovel.COMPLETED -> MR.strings.completed
    SNovel.LICENSED -> MR.strings.licensed
    SNovel.PUBLISHING_FINISHED -> MR.strings.publishing_finished
    SNovel.CANCELLED -> MR.strings.cancelled
    SNovel.ON_HIATUS -> MR.strings.on_hiatus
    else -> MR.strings.unknown_status
}

class NovelDetailScreenModel(
    private val sourceId: Long,
    private val novelUrl: String,
    private val novelTitle: String,
    private val novelRepository: NovelRepository = Injekt.get(),
    private val categoryRepository: NovelCategoryRepository = Injekt.get(),
    private val loader: NovelEntryLoader = NovelEntryLoader(),
) : ScreenModel {

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var novelId: Long = -1
    private var observingFavorite = false
    private var loadJob: Job? = null

    private val _writeErrors = Channel<String>(Channel.BUFFERED)
    val writeErrors = _writeErrors.receiveAsFlow()

    init {
        load()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        load()
    }

    private fun load() {
        // A refresh replaces the load in progress rather than syncing the chapters twice.
        loadJob?.cancel()
        loadJob = screenModelScope.launch {
            runCatching {
                val novel = loader.getOrInsert(sourceId, novelUrl, novelTitle)
                novelId = novel.id
                val (details, chapters) = loader.refresh(novel)
                val info = NovelInfo(
                    cover = details.thumbnail_url,
                    author = details.author,
                    status = details.status,
                    genres = details.genre,
                    description = details.description,
                )
                info to chapters
            }.onSuccess { (info, chapters) ->
                _state.update {
                    it.copy(loading = false, info = info, chapters = chapters)
                }
                if (!observingFavorite) {
                    observingFavorite = true
                    observeFavorite()
                }
            }.onFailure { e ->
                // A load replaced by a refresh must not overwrite the new one's state
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.message ?: "unknown") }
            }
        }
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
        write {
            if (_state.value.favorite) {
                // Categories are cleared only once the novel has left the library
                setFavorite(false)
                novelRepository.setNovelCategories(novelId, emptyList())
                return@write
            }
            // Adding to the library: let the user pick categories first, as Mihon does.
            if (userCategories().isEmpty()) setFavorite(true) else loadCategoryDialog()
        }
    }

    fun showChangeCategoryDialog() {
        if (novelId == -1L) return
        write { loadCategoryDialog() }
    }

    private suspend fun loadCategoryDialog() {
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

    fun dismissCategoryDialog() {
        _state.update { it.copy(categoryDialogSelection = null) }
    }

    fun setCategories(categoryIds: List<Long>) {
        if (novelId == -1L) return
        write {
            novelRepository.setNovelCategories(novelId, categoryIds)
            if (!_state.value.favorite) setFavorite(true)
            // Left open on failure, so the choice is not lost
            dismissCategoryDialog()
        }
    }

    private suspend fun setFavorite(favorite: Boolean) {
        check(novelRepository.updateNovel(NovelUpdate(id = novelId, favorite = favorite))) {
            "Could not update the library"
        }
    }

    /** Library writes: a failure is shown, not a crash, and the steps after it do not run. */
    private fun write(block: suspend () -> Unit) {
        screenModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Library change failed for novel $novelId" }
                _writeErrors.send(e.message ?: e.toString())
            }
        }
    }

    private suspend fun userCategories(): List<Category> =
        categoryRepository.getAllNovelCategories().filterNot(Category::isSystemCategory)

    data class State(
        val loading: Boolean = true,
        val favorite: Boolean = false,
        val info: NovelInfo? = null,
        val chapters: List<NovelChapter> = emptyList(),
        val categoryDialogSelection: ImmutableList<CheckboxState<Category>>? = null,
        val error: String? = null,
    )
}
