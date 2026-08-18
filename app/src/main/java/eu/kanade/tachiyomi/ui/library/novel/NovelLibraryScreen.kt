package eu.kanade.tachiyomi.ui.library.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.library.components.LazyLibraryGrid
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.novel.NovelCatalogScreen
import eu.kanade.tachiyomi.ui.novel.NovelDetailScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import tachiyomi.domain.entries.novel.repository.NovelRepository
import tachiyomi.domain.library.novel.LibraryNovel
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Grid of the novels marked as favorite, mirroring the manga library screen. */
class NovelLibraryScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelLibraryScreenModel() }
        val novels by screenModel.novels.collectAsState()

        Scaffold(
            topBar = {
                AppBar(
                    title = "Novels",
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            actions = persistentListOf(
                                AppBar.Action(
                                    title = "Browse sources",
                                    icon = Icons.Outlined.Explore,
                                    onClick = { navigator.push(NovelCatalogScreen()) },
                                ),
                            ),
                        )
                    },
                )
            },
        ) { contentPadding ->
            val libraryNovels = novels
            when {
                libraryNovels == null -> LoadingScreen(Modifier.padding(contentPadding))
                libraryNovels.isEmpty() -> EmptyScreen(
                    message = "Your novel library is empty",
                    modifier = Modifier.padding(contentPadding),
                )
                else -> NovelLibraryGrid(
                    novels = libraryNovels,
                    contentPadding = contentPadding,
                    onNovelClick = {
                        navigator.push(
                            NovelDetailScreen(
                                sourceId = it.novel.source,
                                novelUrl = it.novel.url,
                                novelTitle = it.novel.title,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun NovelLibraryGrid(
    novels: List<LibraryNovel>,
    contentPadding: PaddingValues,
    onNovelClick: (LibraryNovel) -> Unit,
) {
    LazyLibraryGrid(
        columns = 0,
        contentPadding = contentPadding,
    ) {
        items(novels, key = { it.id }) { libraryNovel ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNovelClick(libraryNovel) },
            ) {
                // Novel covers are plain URLs, so Coil loads them without a dedicated fetcher.
                ItemCover.Book(
                    data = libraryNovel.novel.thumbnailUrl,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = libraryNovel.novel.title,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

class NovelLibraryScreenModel(
    novelRepository: NovelRepository = Injekt.get(),
) : ScreenModel {

    val novels = novelRepository.getLibraryNovelsAsFlow()
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
