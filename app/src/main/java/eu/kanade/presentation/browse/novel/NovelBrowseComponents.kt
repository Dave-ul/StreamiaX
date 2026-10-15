package eu.kanade.presentation.browse.novel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.browse.BaseBrowseItem
import eu.kanade.tachiyomi.novelsource.NovelSource
import eu.kanade.tachiyomi.source.novel.js.JsNovelSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.secondaryItemAlpha

val NovelSource.iconUrl: String?
    get() = (this as? JsNovelSource)?.plugin?.iconUrl

/** Plugin icon from the lnreader index, or a book glyph for built-in sources and failed loads. */
@Composable
fun NovelSourceIcon(iconUrl: String?, modifier: Modifier = Modifier) {
    val iconModifier = modifier
        .size(40.dp)
        .clip(RoundedCornerShape(8.dp))
    if (iconUrl == null) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = iconModifier)
        return
    }
    AsyncImage(
        model = iconUrl,
        contentDescription = null,
        modifier = iconModifier,
        error = rememberVectorPainter(Icons.AutoMirrored.Outlined.MenuBook),
    )
}

/** Same row as the anime/manga source and extension lists: icon, name, secondary line, actions. */
@Composable
fun NovelBrowseRow(
    title: String,
    subtitle: String?,
    iconUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    action: @Composable RowScope.() -> Unit = {},
) {
    BaseBrowseItem(
        modifier = modifier,
        onClickItem = onClick,
        onLongClickItem = onLongClick,
        icon = { NovelSourceIcon(iconUrl) },
        action = action,
        content = {
            Column(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.medium)
                    .weight(1f),
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        modifier = Modifier.secondaryItemAlpha(),
                        text = subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

@Composable
fun NovelListHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = MaterialTheme.padding.small,
        ),
        style = MaterialTheme.typography.header,
    )
}

@Composable
fun languageName(lang: String): String = LocaleHelper.getSourceDisplayName(lang, LocalContext.current)
