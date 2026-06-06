package eu.kanade.tachiyomi.ui.novel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.novel.GutenbergNovelSource
import eu.kanade.tachiyomi.novelsource.model.SNovelChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold

class NovelReaderScreen(
    private val chapterUrl: String,
    private val chapterName: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelReaderScreenModel(chapterUrl) }
        val state by screenModel.state.collectAsState()
        val scrollState = rememberScrollState()

        Scaffold(
            topBar = {
                AppBar(
                    title = chapterName,
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
                else -> Text(
                    text = state.text,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

class NovelReaderScreenModel(private val chapterUrl: String) : ScreenModel {

    private val source = GutenbergNovelSource()

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        screenModelScope.launch {
            runCatching {
                val chapter = SNovelChapter.create().apply { url = chapterUrl }
                val html = source.getChapterText(chapter)
                withContext(Dispatchers.Default) {
                    HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
                }
            }.onSuccess { text ->
                _state.update { it.copy(loading = false, text = text) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "unknown") }
            }
        }
    }

    data class State(
        val loading: Boolean = true,
        val text: String = "",
        val error: String? = null,
    )
}
