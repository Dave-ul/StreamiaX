package eu.kanade.tachiyomi.ui.browse.novel.source

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.novel.NovelBrowseRow
import eu.kanade.presentation.browse.novel.NovelListHeader
import eu.kanade.presentation.browse.novel.iconUrl
import eu.kanade.presentation.browse.novel.languageName
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.source.novel.NovelSourceManager
import eu.kanade.tachiyomi.ui.browse.novel.globalsearch.GlobalNovelSearchScreen
import eu.kanade.tachiyomi.ui.novel.NovelCatalogScreen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Installed novel sources grouped by language, like the anime and manga source tabs. */
@Composable
fun Screen.novelSourcesTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val sourceManager = remember { Injekt.get<NovelSourceManager>() }
    val sources by sourceManager.sources.collectAsState()

    return TabContent(
        titleRes = AYMR.strings.label_novel_sources,
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(GlobalNovelSearchScreen()) },
            ),
        ),
        content = { contentPadding, _ ->
            if (sources.isEmpty()) {
                EmptyScreen(MR.strings.source_empty_screen, Modifier.padding(contentPadding))
            } else {
                val byLanguage = sources
                    .sortedWith(compareBy({ it.lang }, { it.name.lowercase() }))
                    .groupBy { it.lang }
                ScrollbarLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
                    byLanguage.forEach { (lang, group) ->
                        item(key = "header-$lang") { NovelListHeader(languageName(lang)) }
                        items(group, key = { "source-${it.id}" }) { source ->
                            NovelBrowseRow(
                                title = source.name,
                                subtitle = languageName(source.lang),
                                iconUrl = source.iconUrl,
                                onClick = { navigator.push(NovelCatalogScreen(source.id)) },
                                action = {
                                    if (source.supportsLatest) {
                                        TextButton(onClick = { navigator.push(NovelCatalogScreen(source.id, latest = true)) }) {
                                            Text(
                                                text = stringResource(MR.strings.latest),
                                                style = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.primary),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}
