package eu.kanade.tachiyomi.ui.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.novel.GutenbergNovelSource
import eu.kanade.tachiyomi.novelsource.model.SNovel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold

data class ChapterUi(val url: String, val name: String)

class NovelDetailScreen(
    private val novelUrl: String,
    private val novelTitle: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelDetailScreenModel(novelUrl) }
        val state by screenModel.state.collectAsState()

        Scaffold(
            topBar = {
                AppBar(
                    title = novelTitle,
                    navigateUp = navigator::pop,
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
                    items(state.chapters, key = { it.url }) { chapter ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navigator.push(NovelReaderScreen(chapter.url, chapter.name))
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Text(chapter.name, style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

class NovelDetailScreenModel(private val novelUrl: String) : ScreenModel {

    private val source = GutenbergNovelSource()

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        screenModelScope.launch {
            runCatching {
                val novel = SNovel.create().apply { url = novelUrl }
                val details = source.getNovelDetails(novel)
                val chapters = source.getChapterList(details)
                details to chapters
            }.onSuccess { (details, chapters) ->
                _state.update {
                    it.copy(
                        loading = false,
                        description = details.description,
                        chapters = chapters.map { c -> ChapterUi(c.url, c.name) },
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "unknown") }
            }
        }
    }

    data class State(
        val loading: Boolean = true,
        val description: String? = null,
        val chapters: List<ChapterUi> = emptyList(),
        val error: String? = null,
    )
}
