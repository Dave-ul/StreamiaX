package eu.kanade.tachiyomi.ui.history.novel

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.tachiyomi.ui.novel.NovelDetailScreen
import eu.kanade.tachiyomi.ui.novel.NovelReaderScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import tachiyomi.domain.items.novelchapter.model.NovelHistoryItem
import tachiyomi.domain.items.novelchapter.repository.NovelChapterRepository
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Novel page of History: one row per novel, tap to resume, tap the cover for details. */
fun Screen.novelHistoryTab(): TabContent = TabContent(
    titleRes = AYMR.strings.label_novel,
    content = { contentPadding, _ ->
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelHistoryScreenModel() }
        val items by screenModel.items.collectAsState()

        when {
            items == null -> LoadingScreen(Modifier.padding(contentPadding))
            items!!.isEmpty() -> EmptyScreen(MR.strings.information_no_recent_manga, Modifier.padding(contentPadding))
            else -> LazyColumn(contentPadding = contentPadding) {
                items(items!!, key = { it.novelId }) { item ->
                    NovelHistoryRow(
                        item = item,
                        onClickCover = {
                            navigator.push(NovelDetailScreen(item.source, item.novelUrl, item.title))
                        },
                        onClickResume = { navigator.push(NovelReaderScreen(item.chapterId)) },
                    )
                }
            }
        }
    },
)

@Composable
private fun NovelHistoryRow(item: NovelHistoryItem, onClickCover: () -> Unit, onClickResume: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClickResume)
            .height(96.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemCover.Book(
            data = item.thumbnailUrl,
            modifier = Modifier.fillMaxHeight(),
            onClick = onClickCover,
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val readAt = item.readAt?.let { DateUtils.getRelativeTimeSpanString(it.time).toString() }
            Text(
                listOfNotNull(item.chapterName, readAt).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

class NovelHistoryScreenModel(
    chapterRepository: NovelChapterRepository = Injekt.get(),
) : ScreenModel {
    val items = chapterRepository.getRecentHistoryAsFlow()
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
