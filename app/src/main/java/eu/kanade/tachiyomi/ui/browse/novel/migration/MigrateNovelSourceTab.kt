package eu.kanade.tachiyomi.ui.browse.novel.migration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.novel.NovelBrowseRow
import eu.kanade.presentation.browse.novel.NovelListHeader
import eu.kanade.presentation.browse.novel.iconUrl
import eu.kanade.presentation.browse.novel.languageName
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.tachiyomi.source.novel.NovelSourceManager
import eu.kanade.tachiyomi.ui.browse.novel.globalsearch.GlobalNovelSearchScreen
import tachiyomi.domain.entries.novel.repository.NovelRepository
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Sources that hold library novels, with how many; picking one lists its novels to migrate. */
@Composable
fun Screen.migrateNovelSourceTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val sourceManager = remember { Injekt.get<NovelSourceManager>() }
    val novelRepository = remember { Injekt.get<NovelRepository>() }
    val library by remember { novelRepository.getLibraryNovelsAsFlow() }.collectAsState(initial = emptyList())

    return TabContent(
        titleRes = AYMR.strings.label_migration_novel,
        content = { contentPadding, _ ->
            // A novel in several categories shows up once per category in the library view.
            val counts = library.distinctBy { it.id }.groupingBy { it.novel.source }.eachCount()
            if (counts.isEmpty()) {
                EmptyScreen(MR.strings.information_empty_library, Modifier.padding(contentPadding))
            } else {
                ScrollbarLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
                    item(key = "prompt") { NovelListHeader(stringResource(MR.strings.migration_selection_prompt)) }
                    items(counts.entries.sortedByDescending { it.value }, key = { it.key }) { (sourceId, count) ->
                        val source = sourceManager.get(sourceId)
                        NovelBrowseRow(
                            title = source?.name ?: sourceId.toString(),
                            subtitle = source?.let { languageName(it.lang) },
                            iconUrl = source?.iconUrl,
                            onClick = { navigator.push(MigrateNovelScreen(sourceId)) },
                            action = {
                                Text(
                                    count.toString(),
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                        )
                    }
                }
            }
        },
    )
}

/** Library novels of one source; picking one opens the global search to choose the new source. */
class MigrateNovelScreen(private val sourceId: Long) : eu.kanade.presentation.util.Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val sourceName = remember { Injekt.get<NovelSourceManager>().get(sourceId)?.name ?: sourceId.toString() }
        val novels by remember { Injekt.get<NovelRepository>().getNovelFavoritesBySourceId(sourceId) }
            .collectAsState(initial = emptyList())

        Scaffold(
            topBar = { AppBar(title = sourceName, navigateUp = navigator::pop) },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                items(novels.sortedBy { it.title.lowercase() }, key = { it.id }) { novel ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navigator.push(GlobalNovelSearchScreen(novel.title, migrateFromNovelId = novel.id))
                            }
                            .height(88.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ItemCover.Book(data = novel.thumbnailUrl, modifier = Modifier.fillMaxHeight())
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(
                                novel.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
