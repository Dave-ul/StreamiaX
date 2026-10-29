package eu.kanade.tachiyomi.ui.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.novelsource.model.SNovelChapter
import eu.kanade.tachiyomi.source.novel.NovelSourceManager
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.entries.novel.repository.NovelRepository
import tachiyomi.domain.items.novelchapter.model.NovelChapterUpdate
import tachiyomi.domain.items.novelchapter.repository.NovelChapterRepository
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

class NovelReaderScreen(
    private val chapterId: Long,
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelReaderScreenModel(chapterId) }
        val state by screenModel.state.collectAsState()
        val listState = rememberLazyListState()
        val preferences = remember { Injekt.get<PreferenceStore>() }
        val fontSizePref = remember { preferences.getInt("novel_reader_font_size", 18) }
        val lineHeightPref = remember { preferences.getFloat("novel_reader_line_height", 1.5f) }
        val fontSize by fontSizePref.collectAsState()
        val lineHeight by lineHeightPref.collectAsState()
        var showSettings by remember { mutableStateOf(false) }

        // Restore the saved paragraph once the text is loaded, then persist further scrolling.
        LaunchedEffect(state.loading) {
            if (!state.loading && state.startParagraph > 0) {
                listState.scrollToItem(state.startParagraph.coerceAtMost(state.paragraphs.size))
            }
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .drop(1)
                .debounce(SAVE_POSITION_DEBOUNCE_MS)
                .collect(screenModel::savePosition)
        }
        // The chapter counts as read once its end (the navigation row) scrolls into view.
        val reachedEnd by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                info.totalItemsCount > 0 && info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
            }
        }
        LaunchedEffect(reachedEnd, state.loading) {
            if (reachedEnd && !state.loading && state.error == null) screenModel.markRead()
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = state.chapterName,
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(AYMR.strings.novel_reader_text_settings),
                                    icon = Icons.Outlined.FormatSize,
                                    onClick = { showSettings = true },
                                ),
                            ),
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
                    Text(state.error!!, modifier = Modifier.padding(16.dp))
                }
                else -> SelectionContainer {
                    LazyColumn(state = listState, contentPadding = contentPadding) {
                        itemsIndexed(state.paragraphs) { _, paragraph ->
                            Text(
                                text = paragraph,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = fontSize.sp,
                                    lineHeight = (fontSize * lineHeight).sp,
                                ),
                            )
                        }
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(
                                    enabled = state.previousChapterId != null,
                                    onClick = { navigator.replace(NovelReaderScreen(state.previousChapterId!!)) },
                                ) { Text(stringResource(MR.strings.action_previous_chapter)) }
                                TextButton(
                                    enabled = state.nextChapterId != null,
                                    onClick = { navigator.replace(NovelReaderScreen(state.nextChapterId!!)) },
                                ) { Text(stringResource(MR.strings.action_next_chapter)) }
                            }
                        }
                    }
                }
            }
        }

        if (showSettings) {
            ModalBottomSheet(onDismissRequest = { showSettings = false }) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
                    Text("${stringResource(AYMR.strings.novel_reader_font_size)}: $fontSize")
                    Slider(
                        value = fontSize.toFloat(),
                        onValueChange = { fontSizePref.set(it.toInt()) },
                        valueRange = 12f..32f,
                        steps = 19,
                    )
                    Text("${stringResource(AYMR.strings.novel_reader_line_height)}: ${"%.1f".format(lineHeight)}")
                    Slider(
                        value = lineHeight,
                        onValueChange = { lineHeightPref.set((it * 10).toInt() / 10f) },
                        valueRange = 1.0f..2.4f,
                        steps = 13,
                    )
                }
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
                val paragraphs = withContext(Dispatchers.Default) { htmlToParagraphs(html) }
                val siblings = chapterRepository.getChapterByNovelId(chapter.novelId).sortedBy { it.sourceOrder }
                val index = siblings.indexOfFirst { it.id == chapterId }
                _state.update {
                    it.copy(
                        loading = false,
                        chapterName = chapter.name,
                        paragraphs = paragraphs,
                        startParagraph = chapter.lastPosition.toInt(),
                        previousChapterId = siblings.getOrNull(index - 1)?.id,
                        nextChapterId = siblings.getOrNull(index + 1)?.id,
                    )
                }
                // Shows up in History right away; the reading time is added when leaving.
                chapterRepository.upsertHistory(chapterId, readAt = Date(), sessionReadDuration = 0)
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "unknown") }
            }
        }
    }

    fun savePosition(paragraph: Int) {
        screenModelScope.launch {
            chapterRepository.update(NovelChapterUpdate(id = chapterId, lastPosition = paragraph.toLong()))
        }
    }

    fun markRead() {
        screenModelScope.launch {
            chapterRepository.update(NovelChapterUpdate(id = chapterId, read = true))
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onDispose() {
        if (_state.value.loading || _state.value.error != null) return
        val duration = System.currentTimeMillis() - openedAt
        // screenModelScope is being cancelled, so the last write goes through a global scope.
        GlobalScope.launch(Dispatchers.IO) {
            chapterRepository.upsertHistory(chapterId, readAt = Date(), sessionReadDuration = duration)
        }
    }

    /** Blank lines separate paragraphs; `<p>` and `<div>` become blank lines in legacy mode. */
    private fun htmlToParagraphs(html: String): List<String> =
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    data class State(
        val loading: Boolean = true,
        val chapterName: String = "",
        val paragraphs: List<String> = emptyList(),
        val startParagraph: Int = 0,
        val previousChapterId: Long? = null,
        val nextChapterId: Long? = null,
        val error: String? = null,
    )
}
