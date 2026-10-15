package eu.kanade.tachiyomi.ui.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.novelsource.model.NovelsPage
import eu.kanade.tachiyomi.source.novel.NovelSourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class NovelUi(val url: String, val title: String, val author: String?, val cover: String?)

/** Browses a single novel source, picked in [NovelSourcesScreen]. */
class NovelCatalogScreen(
    private val sourceId: Long? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelCatalogScreenModel(sourceId) }
        val state by screenModel.state.collectAsState()
        var query by remember { mutableStateOf("") }
        val gridState = rememberLazyGridState()

        // Infinite scroll: ask for the next page when the last cell comes into view.
        val nearEnd by remember {
            derivedStateOf {
                val info = gridState.layoutInfo
                info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
            }
        }
        LaunchedEffect(nearEnd, state.novels.size) {
            if (nearEnd) screenModel.loadNextPage()
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = state.sourceName,
                    navigateUp = navigator::pop,
                )
            },
        ) { contentPadding ->
            Column(modifier = Modifier.padding(contentPadding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(MR.strings.action_search_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { screenModel.setListing(Listing.Search(query)) }),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.listing == Listing.Popular,
                        onClick = { query = ""; screenModel.setListing(Listing.Popular) },
                        label = { Text(stringResource(MR.strings.popular)) },
                    )
                    if (state.supportsLatest) {
                        FilterChip(
                            selected = state.listing == Listing.Latest,
                            onClick = { query = ""; screenModel.setListing(Listing.Latest) },
                            label = { Text(stringResource(MR.strings.latest)) },
                        )
                    }
                }

                when {
                    state.novels.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.novels.isEmpty() && state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error!!, modifier = Modifier.padding(16.dp))
                            TextButton(onClick = screenModel::retry) { Text(stringResource(MR.strings.action_retry)) }
                        }
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.novels, key = { it.url }) { novel ->
                            Column(
                                modifier = Modifier.clickable {
                                    navigator.push(
                                        NovelDetailScreen(
                                            sourceId = screenModel.sourceId,
                                            novelUrl = novel.url,
                                            novelTitle = novel.title,
                                        ),
                                    )
                                },
                            ) {
                                ItemCover.Book(data = novel.cover, modifier = Modifier.fillMaxWidth())
                                Text(
                                    novel.title,
                                    modifier = Modifier.padding(top = 4.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (state.loading || state.error != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                                    if (state.loading) {
                                        CircularProgressIndicator()
                                    } else {
                                        TextButton(onClick = screenModel::retry) {
                                            Text(stringResource(MR.strings.action_retry))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

sealed interface Listing {
    data object Popular : Listing
    data object Latest : Listing
    data class Search(val query: String) : Listing
}

class NovelCatalogScreenModel(
    requestedSourceId: Long?,
    private val sourceManager: NovelSourceManager = Injekt.get(),
) : ScreenModel {

    private val source = requestedSourceId?.let(sourceManager::get)
        ?: sourceManager.sources.value.first()

    val sourceId: Long = source.id

    private val _state = MutableStateFlow(State(sourceName = source.name, supportsLatest = source.supportsLatest))
    val state = _state.asStateFlow()

    private var nextPage = 1
    private var hasNextPage = true
    private var job: Job? = null

    init {
        loadNextPage()
    }

    fun setListing(listing: Listing) {
        val effective = if (listing is Listing.Search && listing.query.isBlank()) Listing.Popular else listing
        job?.cancel()
        nextPage = 1
        hasNextPage = true
        _state.update { it.copy(listing = effective, novels = emptyList(), error = null, loading = false) }
        loadNextPage()
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        loadNextPage()
    }

    fun loadNextPage() {
        val current = _state.value
        if (current.loading || current.error != null || !hasNextPage) return
        _state.update { it.copy(loading = true) }
        val page = nextPage
        job = screenModelScope.launch {
            runCatching { fetch(current.listing, page) }
                .onSuccess { result ->
                    nextPage = page + 1
                    // Some plugins only guess hasNextPage; an empty page ends the list either way.
                    hasNextPage = result.hasNextPage && result.novels.isNotEmpty()
                    val known = _state.value.novels.mapTo(HashSet()) { it.url }
                    val added = result.novels
                        .filter { known.add(it.url) }
                        .map { NovelUi(it.url, it.title, it.author, it.thumbnail_url) }
                    _state.update { it.copy(loading = false, novels = it.novels + added) }
                }
                .onFailure { e ->
                    // A listing switch cancels the old request; that is not an error to show.
                    if (e is CancellationException) return@onFailure
                    _state.update { it.copy(loading = false, error = e.message ?: "unknown") }
                }
        }
    }

    private suspend fun fetch(listing: Listing, page: Int): NovelsPage = when (listing) {
        Listing.Popular -> source.getPopularNovels(page)
        Listing.Latest -> source.getLatestUpdates(page)
        is Listing.Search -> source.getSearchNovels(page, listing.query)
    }

    data class State(
        val loading: Boolean = false,
        val sourceName: String = "",
        val supportsLatest: Boolean = false,
        val listing: Listing = Listing.Popular,
        val novels: List<NovelUi> = emptyList(),
        val error: String? = null,
    )
}
