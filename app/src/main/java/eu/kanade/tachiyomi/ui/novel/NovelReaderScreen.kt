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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
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
import eu.kanade.tachiyomi.novelsource.model.SNovelChapter
import eu.kanade.tachiyomi.source.novel.NovelSourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.entries.novel.repository.NovelRepository
import tachiyomi.domain.items.novelchapter.model.NovelChapterUpdate
import tachiyomi.domain.items.novelchapter.repository.NovelChapterRepository
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

class NovelReaderScreen(
    private val chapterId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelReaderScreenModel(chapterId) }
        val state by screenModel.state.collectAsState()
        val scrollState = rememberScrollState()

        // Restore the saved offset once the text is laid out, then persist further scrolling.
        LaunchedEffect(state.loading) {
            if (!state.loading && state.startPosition > 0) {
                scrollState.scrollTo(state.startPosition)
            }
        }
        LaunchedEffect(scrollState) {
            snapshotFlow { scrollState.value }
                .drop(1)
                .debounce(SAVE_POSITION_DEBOUNCE_MS)
                .collect(screenModel::savePosition)
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = state.chapterName,
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

    private companion object {
        const val SAVE_POSITION_DEBOUNCE_MS = 500L
    }
}

class NovelReaderScreenModel(
    private val chapterId: Long,
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
    private val chapterRepository: NovelChapterRepository = Injekt.get(),
) : ScreenModel {

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val openedAt = System.currentTimeMillis()

    init {
        load()
    }

    private fun load() {
        screenModelScope.launch {
            runCatching {
                val chapter = chapterRepository.getChapterById(chapterId)
                    ?: error("Chapter $chapterId not found")
                val novel = novelRepository.getNovelById(chapter.novelId)
                val source = sourceManager.get(novel.source)
                    ?: error("Source ${novel.source} not available")

                val html = source.getChapterText(
                    SNovelChapter.create().also {
                        it.url = chapter.url
                        it.name = chapter.name
                    },
                )
                val text = withContext(Dispatchers.Default) {
                    HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
                }
                Triple(chapter.name, text, chapter.lastPosition.toInt())
            }.onSuccess { (name, text, position) ->
                _state.update {
                    it.copy(
                        loading = false,
                        chapterName = name,
                        text = text,
                        startPosition = position,
                    )
                }
                markRead()
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "unknown") }
            }
        }
    }

    fun savePosition(position: Int) {
        screenModelScope.launch {
            chapterRepository.update(
                NovelChapterUpdate(id = chapterId, lastPosition = position.toLong()),
            )
        }
    }

    private fun markRead() {
        screenModelScope.launch {
            chapterRepository.update(NovelChapterUpdate(id = chapterId, read = true))
            chapterRepository.upsertHistory(
                chapterId = chapterId,
                readAt = Date(),
                sessionReadDuration = System.currentTimeMillis() - openedAt,
            )
        }
    }

    data class State(
        val loading: Boolean = true,
        val chapterName: String = "",
        val text: String = "",
        val startPosition: Int = 0,
        val error: String? = null,
    )
}
