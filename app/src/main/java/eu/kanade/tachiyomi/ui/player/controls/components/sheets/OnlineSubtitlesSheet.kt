package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.player.components.PlayerSheet
import eu.kanade.tachiyomi.ui.player.subtitles.OnlineSubtitles
import eu.kanade.tachiyomi.ui.player.subtitles.OnlineSubtitlesController
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun OnlineSubtitlesSheet(
    state: OnlineSubtitlesController.State,
    onSearch: (title: String, language: String) -> Unit,
    onAdd: (OnlineSubtitles.Result) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = state.query ?: return
    var title by rememberSaveable(query.episode, query.imdbId) { mutableStateOf(query.title) }
    var languageMenu by remember { mutableStateOf(false) }

    PlayerSheet(onDismissRequest) {
        Column(modifier) {
            TrackSheetTitle(title = stringResource(AYMR.strings.player_online_subtitles))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(AYMR.strings.player_online_subtitles_title)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(title, query.language) }),
                )
                Box {
                    TextButton(onClick = { languageMenu = true }) {
                        Text(OnlineSubtitles.languageName(query.language))
                    }
                    DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                        OnlineSubtitles.LANGUAGES.forEach { code ->
                            DropdownMenuItem(
                                text = { Text(OnlineSubtitles.languageName(code)) },
                                onClick = {
                                    languageMenu = false
                                    onSearch(title, code)
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = { onSearch(title, query.language) }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(AYMR.strings.player_online_subtitles_search),
                    )
                }
            }
            if (state.searching) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.padding.medium),
                )
            }
            val message = when {
                state.searching -> null
                state.error != null -> stringResource(AYMR.strings.player_online_subtitles_error, state.error)
                state.searched && state.results.isEmpty() -> stringResource(AYMR.strings.player_online_subtitles_empty)
                else -> null
            }
            if (message != null) {
                Text(
                    text = message,
                    modifier = Modifier.padding(MaterialTheme.padding.medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn {
                items(state.results, key = { it.id }) { result ->
                    OnlineSubtitleRow(
                        result = result,
                        downloading = state.downloadingId == result.id,
                        added = result.id in state.addedIds,
                        onClick = { onAdd(result) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineSubtitleRow(
    result: OnlineSubtitles.Result,
    downloading: Boolean,
    added: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !downloading, onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = result.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            val details = buildList {
                add(result.provider)
                add(result.format.uppercase())
                if (result.downloads > 0) {
                    add(stringResource(AYMR.strings.player_online_subtitles_downloads, result.downloads))
                }
                if (result.hashMatch) add(stringResource(AYMR.strings.player_online_subtitles_file_match))
                if (result.hearingImpaired) add(stringResource(AYMR.strings.player_online_subtitles_hi))
            }
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (result.hashMatch) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        when {
            downloading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            added -> Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
