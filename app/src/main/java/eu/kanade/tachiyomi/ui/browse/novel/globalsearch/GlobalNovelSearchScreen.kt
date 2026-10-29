package eu.kanade.tachiyomi.ui.browse.novel.globalsearch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.GlobalSearchResultItem
import eu.kanade.presentation.browse.novel.languageName
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.novelsource.NovelSource
import eu.kanade.tachiyomi.novelsource.model.SNovel
import eu.kanade.tachiyomi.source.novel.NovelSourceManager
import eu.kanade.tachiyomi.ui.browse.novel.migration.NovelMigrator
import eu.kanade.tachiyomi.ui.novel.NovelCatalogScreen
import eu.kanade.tachiyomi.ui.novel.NovelDetailScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.entries.novel.repository.NovelRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Searches every installed novel source at once, like the anime and manga global search. With
 * [migrateFromNovelId] it is the second step of a migration: picking a result offers Copy/Migrate.
 */
class GlobalNovelSearchScreen(
    private val initialQuery: String = "",
    private val migrateFromNovelId: Long? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { GlobalNovelSearchScreenModel(initialQuery, migrateFromNovelId) }
        val state by screenModel.state.collectAsState()
        var pending by remember { mutableStateOf<Pair<NovelSource, SNovel>?>(null) }

        Scaffold(
            topBar = {
                SearchToolbar(
                    titleContent = {
                        Text(
                            stringResource(if (migrateFromNovelId != null) MR.strings.action_migrate else MR.strings.action_global_search),
                        )
                    },
                    searchQuery = state.query,
                    onChangeSearchQuery = screenModel::updateQuery,
                    onSearch = screenModel::search,
                    navigateUp = navigator::pop,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                items(state.results, key = { it.source.id }) { result ->
                    GlobalSearchResultItem(
                        title = result.source.name,
                        subtitle = languageName(result.source.lang),
                        onClick = { navigator.push(NovelCatalogScreen(result.source.id, query = state.searched)) },
                    ) {
                        when {
                            result.loading -> GlobalSearchLoadingResultItem()
                            result.error != null -> GlobalSearchErrorResultItem(result.error)
                            result.novels.isEmpty() -> Text(
                                stringResource(MR.strings.no_results_found),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            else -> LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(result.novels, key = { it.url }) { novel ->
                                    NovelSearchCard(novel) {
                                        if (migrateFromNovelId == null) {
                                            navigator.push(NovelDetailScreen(result.source.id, novel.url, novel.title))
                                        } else {
                                            pending = result.source to novel
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        pending?.let { (source, novel) ->
            AlertDialog(
                onDismissRequest = {
                    pending = null
                    screenModel.clearMigrationError()
                },
                title = { Text(stringResource(MR.strings.action_migrate)) },
                text = {
                    Text(
                        listOfNotNull("${novel.title} · ${source.name}", state.migrationError)
                            .joinToString("\n\n"),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !state.migrating,
                        onClick = { screenModel.migrate(source, novel, replace = true) { navigator.pop() } },
                    ) { Text(stringResource(MR.strings.migrate)) }
                },
                dismissButton = {
                    TextButton(
                        enabled = !state.migrating,
                        onClick = { screenModel.migrate(source, novel, replace = false) { navigator.pop() } },
                    ) { Text(stringResource(MR.strings.copy)) }
                },
            )
        }
    }
}

@Composable
private fun NovelSearchCard(novel: SNovel, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick),
    ) {
        ItemCover.Book(data = novel.thumbnail_url, modifier = Modifier.fillMaxWidth())
        Text(
            novel.title,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

class GlobalNovelSearchScreenModel(
    initialQuery: String,
    private val migrateFromNovelId: Long?,
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
    private val migrator: NovelMigrator = NovelMigrator(),
) : ScreenModel {

    private val _state = MutableStateFlow(State(query = initialQuery))
    val state = _state.asStateFlow()

    private val jobs = mutableListOf<Job>()

    init {
        if (initialQuery.isNotBlank()) search(initialQuery)
    }

    fun updateQuery(query: String?) {
        _state.update { it.copy(query = query) }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        jobs.forEach { it.cancel() }
        jobs.clear()
        val sources = sourceManager.sources.value.sortedWith(compareBy({ it.lang }, { it.name.lowercase() }))
        _state.update { state -> state.copy(searched = query, results = sources.map { SourceResult(it) }) }
        sources.forEach { source ->
            jobs += screenModelScope.launch {
                val result = try {
                    SourceResult(source, loading = false, novels = source.getSearchNovels(1, query).novels)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    SourceResult(source, loading = false, error = e.message ?: e.toString())
                }
                _state.update { state ->
                    // Sources with results float to the top, as in the anime/manga global search.
                    val results = state.results.map { if (it.source.id == source.id) result else it }
                    state.copy(results = results.sortedBy { it.loading || it.error != null || it.novels.isEmpty() })
                }
            }
        }
    }

    fun migrate(source: NovelSource, novel: SNovel, replace: Boolean, onDone: () -> Unit) {
        val oldId = migrateFromNovelId ?: return
        _state.update { it.copy(migrating = true, migrationError = null) }
        screenModelScope.launch {
            runCatching {
                migrator.migrate(novelRepository.getNovelById(oldId), source.id, novel.url, novel.title, replace)
            }.onSuccess {
                _state.update { it.copy(migrating = false) }
                onDone()
            }.onFailure { e ->
                _state.update { it.copy(migrating = false, migrationError = e.message ?: e.toString()) }
            }
        }
    }

    fun clearMigrationError() {
        _state.update { it.copy(migrationError = null) }
    }

    data class SourceResult(
        val source: NovelSource,
        val loading: Boolean = true,
        val novels: List<SNovel> = emptyList(),
        val error: String? = null,
    )

    data class State(
        val query: String? = null,
        val searched: String = "",
        val results: List<SourceResult> = emptyList(),
        val migrating: Boolean = false,
        val migrationError: String? = null,
    )
}
