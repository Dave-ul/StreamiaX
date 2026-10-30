package eu.kanade.tachiyomi.ui.novel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.novel.reader.NovelPage
import eu.kanade.tachiyomi.ui.novel.reader.NovelReaderPreferences
import eu.kanade.tachiyomi.ui.novel.reader.NovelReaderSettingsSheet
import eu.kanade.tachiyomi.ui.novel.reader.ReaderFont
import eu.kanade.tachiyomi.ui.novel.reader.pageOfParagraph
import eu.kanade.tachiyomi.ui.novel.reader.paginate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

/**
 * Novel chapter reader: scrolling or paged text, reader themes, text-to-speech and
 * translation. Features follow lnreader and QuickNovel; the paged mode and translation
 * flow follow Dartotsu. Defaults are described in [NovelReaderPreferences].
 *
 * @param autoSpeak start reading aloud as soon as the chapter loads (speech continuing
 * from the previous chapter).
 */
class NovelReaderScreen(
    private val chapterId: Long,
    private val autoSpeak: Boolean = false,
) : Screen() {

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelReaderScreenModel(chapterId, autoSpeak) }
        val state by screenModel.state.collectAsState()
        val speaking by screenModel.speaking.collectAsState()
        val prefs = screenModel.preferences
        val fontSize by prefs.fontSize().collectPreferenceAsState()
        val lineHeight by prefs.lineHeight().collectPreferenceAsState()
        val sidePadding by prefs.sidePadding().collectPreferenceAsState()
        val justify by prefs.justify().collectPreferenceAsState()
        val font by prefs.font().collectPreferenceAsState()
        val theme by prefs.theme().collectPreferenceAsState()
        val paged by prefs.pagedMode().collectPreferenceAsState()
        val volumeKeys by prefs.volumeKeys().collectPreferenceAsState()
        val keepScreenOn by prefs.keepScreenOn().collectPreferenceAsState()
        val translate by prefs.translate().collectPreferenceAsState()
        var showSettings by remember { mutableStateOf(false) }

        val background = theme.background?.let { Color(it) } ?: MaterialTheme.colorScheme.background
        val textColor = theme.text?.let { Color(it) } ?: MaterialTheme.colorScheme.onBackground
        val style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * lineHeight).sp,
            fontFamily = font.toFontFamily(),
            textAlign = if (justify) TextAlign.Justify else TextAlign.Start,
            color = textColor,
        )

        // Speech reached the end of the chapter: open the next one and keep reading.
        LaunchedEffect(screenModel) {
            screenModel.speakNext.collect { navigator.replace(NovelReaderScreen(it, autoSpeak = true)) }
        }

        val view = LocalView.current
        DisposableEffect(keepScreenOn, speaking) {
            view.keepScreenOn = keepScreenOn || speaking != null
            onDispose { view.keepScreenOn = false }
        }

        val listState = rememberLazyListState()
        // Null until paginated. One extra page holds the chapter navigation, like the last row
        // of the scrolling reader.
        var pages by remember { mutableStateOf<List<NovelPage>?>(null) }
        val pagerState = rememberPagerState { (pages?.size ?: 0) + 1 }
        val scope = rememberCoroutineScope()
        val focusRequester = remember { FocusRequester() }

        fun currentParagraph(): Int = if (paged) {
            pages?.let { it.getOrNull(pagerState.currentPage)?.firstParagraph ?: state.text.lastIndex }
                ?: state.savedParagraph
        } else {
            listState.firstVisibleItemIndex.coerceAtMost(state.text.lastIndex)
        }

        fun turn(forward: Boolean) {
            scope.launch {
                if (paged) {
                    val target = pagerState.currentPage + if (forward) 1 else -1
                    pagerState.animateScrollToPage(target.coerceIn(0, pagerState.pageCount - 1))
                } else {
                    val viewport = listState.layoutInfo.viewportSize.height * PAGE_SCROLL_FRACTION
                    listState.animateScrollBy(if (forward) viewport else -viewport)
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = state.chapterName,
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                if (speaking == null) {
                                    AppBar.Action(
                                        title = stringResource(AYMR.strings.novel_reader_speak),
                                        icon = Icons.AutoMirrored.Outlined.VolumeUp,
                                        onClick = { screenModel.startSpeech(currentParagraph()) },
                                    )
                                } else {
                                    AppBar.Action(
                                        title = stringResource(AYMR.strings.novel_reader_stop_speaking),
                                        icon = Icons.Outlined.Stop,
                                        onClick = screenModel::stopSpeech,
                                    )
                                },
                                AppBar.Action(
                                    title = stringResource(
                                        if (translate) {
                                            AYMR.strings.novel_reader_show_original
                                        } else {
                                            AYMR.strings.novel_reader_translate
                                        },
                                    ),
                                    icon = Icons.Outlined.Translate,
                                    onClick = { screenModel.setTranslation(!translate) },
                                ),
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
            Box(
                Modifier
                    .fillMaxSize()
                    .background(background)
                    .padding(contentPadding)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val isVolume = event.key == Key.VolumeDown || event.key == Key.VolumeUp
                        if (!volumeKeys || !isVolume) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) turn(forward = event.key == Key.VolumeDown)
                        true
                    },
            ) {
                CompositionLocalProvider(LocalContentColor provides textColor) {
                    when {
                        state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        state.error != null -> Text(
                            text = state.error.orEmpty(),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                        )
                        paged -> PagedText(
                            state = state,
                            style = style,
                            sidePadding = sidePadding,
                            speaking = speaking,
                            pagerState = pagerState,
                            pages = pages,
                            onPagesChange = { pages = it },
                            onTurn = ::turn,
                            onPositionShown = screenModel::onPositionShown,
                            onPosition = screenModel::savePosition,
                            onRead = screenModel::markRead,
                            onChapter = { navigator.replace(NovelReaderScreen(it)) },
                        )
                        else -> ScrollingText(
                            state = state,
                            style = style,
                            sidePadding = sidePadding,
                            speaking = speaking,
                            listState = listState,
                            onPositionShown = screenModel::onPositionShown,
                            onPosition = screenModel::savePosition,
                            onRead = screenModel::markRead,
                            onChapter = { navigator.replace(NovelReaderScreen(it)) },
                        )
                    }
                    if (state.translating) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                    if (state.speechUnavailable) {
                        Text(
                            text = stringResource(AYMR.strings.novel_reader_speech_unavailable),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    state.translationError?.let {
                        Text(
                            text = stringResource(AYMR.strings.novel_reader_translation_failed, it),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        LaunchedEffect(state.loading) {
            if (!state.loading) focusRequester.requestFocus()
        }

        if (showSettings) {
            NovelReaderSettingsSheet(
                preferences = prefs,
                translationTarget = screenModel.translationTarget(),
                onTranslationLanguage = screenModel::setTranslationLanguage,
                onDismissRequest = {
                    showSettings = false
                    focusRequester.requestFocus()
                },
            )
        }
    }

    private companion object {
        const val SAVE_POSITION_DEBOUNCE_MS = 500L
        const val PAGE_SCROLL_FRACTION = 0.85f
        val PARAGRAPH_GAP = 16.dp
    }

    @Composable
    private fun ScrollingText(
        state: NovelReaderScreenModel.State,
        style: TextStyle,
        sidePadding: Int,
        speaking: Int?,
        listState: LazyListState,
        onPositionShown: (Int) -> Unit,
        onPosition: (Int) -> Unit,
        onRead: () -> Unit,
        onChapter: (Long) -> Unit,
    ) {
        // Restore the saved paragraph once the text is loaded, then persist further scrolling.
        LaunchedEffect(Unit) {
            if (state.savedParagraph > 0) {
                listState.scrollToItem(state.savedParagraph.coerceAtMost(state.text.size))
            }
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .drop(1)
                .onEach { onPositionShown(it) }
                .debounce(SAVE_POSITION_DEBOUNCE_MS)
                .collect(onPosition)
        }
        // The chapter counts as read once its end (the navigation row) scrolls into view.
        val reachedEnd by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                info.totalItemsCount > 0 && info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
            }
        }
        LaunchedEffect(reachedEnd) {
            if (reachedEnd) onRead()
        }
        // Follow the paragraph being read aloud when it scrolls out of view.
        LaunchedEffect(speaking) {
            val index = speaking ?: return@LaunchedEffect
            val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (index !in visible.dropLast(1)) listState.animateScrollToItem(index)
        }

        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(state.text) { index, paragraph ->
                    Paragraph(
                        text = paragraph,
                        style = style,
                        highlighted = index == speaking,
                        modifier = Modifier.padding(horizontal = sidePadding.dp, vertical = PARAGRAPH_GAP / 2),
                    )
                }
                item { ChapterNavigation(state, onChapter) }
            }
        }
    }

    @Composable
    private fun PagedText(
        state: NovelReaderScreenModel.State,
        style: TextStyle,
        sidePadding: Int,
        speaking: Int?,
        pagerState: PagerState,
        pages: List<NovelPage>?,
        onPagesChange: (List<NovelPage>?) -> Unit,
        onTurn: (forward: Boolean) -> Unit,
        onPositionShown: (Int) -> Unit,
        onPosition: (Int) -> Unit,
        onRead: () -> Unit,
        onChapter: (Long) -> Unit,
    ) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = with(density) { (maxWidth - sidePadding.dp * 2).roundToPx() }
            val height = with(density) { (maxHeight - PARAGRAPH_GAP * 2).roundToPx() }
            val gap = with(density) { PARAGRAPH_GAP.roundToPx() }
            val text = state.text

            // Pages from an earlier visit would point at a stale spot: start over when leaving.
            DisposableEffect(Unit) {
                onDispose { onPagesChange(null) }
            }
            // Re-paginate when the text, the style or the screen size change, keeping the reading spot.
            LaunchedEffect(text, style, width, height) {
                val keep = pages?.getOrNull(pagerState.currentPage)?.firstParagraph ?: state.savedParagraph
                val result = withContext(Dispatchers.Default) { paginate(text, measurer, style, width, height, gap) }
                onPagesChange(result)
                pagerState.scrollToPage(pageOfParagraph(result, keep))
            }
            if (pages == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@BoxWithConstraints
            }
            LaunchedEffect(pagerState, pages) {
                snapshotFlow { pagerState.currentPage }
                    .drop(1)
                    .onEach { page -> pages.getOrNull(page)?.let { onPositionShown(it.firstParagraph) } }
                    .debounce(SAVE_POSITION_DEBOUNCE_MS)
                    .collect { page ->
                        pages.getOrNull(page)?.let { onPosition(it.firstParagraph) }
                        if (pages.isNotEmpty() && page >= pages.lastIndex) onRead()
                    }
            }
            LaunchedEffect(speaking, pages) {
                val index = speaking ?: return@LaunchedEffect
                val page = pages.indexOfFirst { page -> page.slices.any { it.paragraph == index } }
                if (page >= 0 && page != pagerState.currentPage) pagerState.animateScrollToPage(page)
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Taps on the outer thirds turn the page, like a book.
                        detectTapGestures { offset ->
                            when {
                                offset.x < size.width / 3f -> onTurn(false)
                                offset.x > size.width * 2 / 3f -> onTurn(true)
                            }
                        }
                    },
            ) { pageIndex ->
                val page = pages.getOrNull(pageIndex)
                if (page == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ChapterNavigation(state, onChapter)
                    }
                    return@HorizontalPager
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = sidePadding.dp, vertical = PARAGRAPH_GAP),
                ) {
                    page.slices.forEachIndexed { i, slice ->
                        // Pages can lag one frame behind a text change (translation on/off).
                        val paragraph = text.getOrNull(slice.paragraph) ?: return@forEachIndexed
                        if (i > 0) Spacer(Modifier.height(PARAGRAPH_GAP))
                        val end = slice.end.coerceAtMost(paragraph.length)
                        Paragraph(
                            text = paragraph.substring(slice.start.coerceAtMost(end), end).trimEnd(),
                            style = style,
                            highlighted = slice.paragraph == speaking,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Paragraph(
        text: String,
        style: TextStyle,
        highlighted: Boolean,
        modifier: Modifier = Modifier,
    ) {
        val highlight by animateFloatAsState(if (highlighted) HIGHLIGHT_ALPHA else 0f, label = "speech")
        Text(
            text = text,
            style = style,
            modifier = modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = highlight)),
        )
    }

    @Composable
    private fun ChapterNavigation(state: NovelReaderScreenModel.State, onChapter: (Long) -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                enabled = state.previousChapterId != null,
                onClick = { state.previousChapterId?.let(onChapter) },
            ) { Text(stringResource(MR.strings.action_previous_chapter)) }
            TextButton(
                enabled = state.nextChapterId != null,
                onClick = { state.nextChapterId?.let(onChapter) },
            ) { Text(stringResource(MR.strings.action_next_chapter)) }
        }
    }
}

private const val HIGHLIGHT_ALPHA = 0.18f

private fun ReaderFont.toFontFamily(): FontFamily = when (this) {
    ReaderFont.DEFAULT -> FontFamily.Default
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.SANS_SERIF -> FontFamily.SansSerif
    ReaderFont.MONOSPACE -> FontFamily.Monospace
}
