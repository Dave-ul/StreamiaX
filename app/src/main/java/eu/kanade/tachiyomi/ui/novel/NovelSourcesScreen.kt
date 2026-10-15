package eu.kanade.tachiyomi.ui.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.novelsource.NovelSource
import eu.kanade.tachiyomi.source.novel.NovelSourceManager
import eu.kanade.tachiyomi.source.novel.js.JsNovelSource
import eu.kanade.tachiyomi.source.novel.js.LnReaderPlugin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Picks a novel source to browse and installs, updates or removes lnreader plugins. */
class NovelSourcesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelSourcesScreenModel() }
        val state by screenModel.state.collectAsState()
        val sources by screenModel.sources.collectAsState()
        var query by remember { mutableStateOf("") }

        val installedIds = sources.mapNotNull { (it as? JsNovelSource)?.plugin?.id }.toSet()
        val latest = state.available.associateBy { it.id }
        val available = state.available
            .filter { it.id !in installedIds && it.matches(query) }
            .sortedWith(compareBy({ it.lang }, { it.name.lowercase() }))

        Scaffold(
            topBar = {
                AppBar(
                    title = "Novel sources",
                    navigateUp = navigator::pop,
                )
            },
        ) { contentPadding ->
            LazyColumn(modifier = Modifier.padding(contentPadding)) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        label = { Text("Filter sources") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        singleLine = true,
                    )
                }
                state.message?.let { message ->
                    item {
                        Text(
                            message,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                item { SectionHeader("Installed") }
                items(sources.filter { it.matches(query) }.sortedBy { it.name.lowercase() }, key = { it.id }) { source ->
                    val plugin = (source as? JsNovelSource)?.plugin
                    val update = plugin?.let { latest[it.id] }?.takeIf { it.version != plugin.version }
                    SourceRow(
                        title = source.name,
                        subtitle = listOfNotNull(source.lang, plugin?.version?.let { "v$it" }).joinToString(" · "),
                        onClick = { navigator.push(NovelCatalogScreen(source.id)) },
                    ) {
                        when {
                            plugin != null && plugin.id in state.busy -> Busy()
                            plugin != null -> {
                                if (update != null) TextButton(onClick = { screenModel.install(update) }) { Text("Update") }
                                TextButton(onClick = { screenModel.uninstall(source as JsNovelSource) }) { Text("Remove") }
                            }
                        }
                    }
                }

                item { SectionHeader("Available lnreader plugins") }
                when {
                    state.loadingIndex -> item { Busy(Modifier.padding(16.dp)) }
                    state.indexError != null -> item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text("Could not load the plugin list: ${state.indexError}")
                            TextButton(onClick = screenModel::loadIndex) { Text("Retry") }
                        }
                    }
                    else -> items(available, key = { "plugin:${it.id}" }) { plugin ->
                        SourceRow(
                            title = plugin.name,
                            subtitle = "${plugin.lang} · v${plugin.version}",
                            onClick = null,
                        ) {
                            if (plugin.id in state.busy) {
                                Busy()
                            } else {
                                TextButton(onClick = { screenModel.install(plugin) }) { Text("Install") }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionHeader(text: String) {
        Text(
            text,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    @Composable
    private fun SourceRow(
        title: String,
        subtitle: String,
        onClick: (() -> Unit)?,
        actions: @Composable () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            actions()
        }
        HorizontalDivider()
    }

    @Composable
    private fun Busy(modifier: Modifier = Modifier) {
        Box(modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    }

    private fun LnReaderPlugin.matches(query: String) =
        query.isBlank() || name.contains(query, ignoreCase = true) || lang.contains(query, ignoreCase = true)

    private fun NovelSource.matches(query: String) =
        query.isBlank() || name.contains(query, ignoreCase = true) || lang.contains(query, ignoreCase = true)
}

class NovelSourcesScreenModel(
    private val sourceManager: NovelSourceManager = Injekt.get(),
) : ScreenModel {

    val sources = sourceManager.sources

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        loadIndex()
    }

    fun loadIndex() {
        _state.update { it.copy(loadingIndex = true, indexError = null) }
        screenModelScope.launch {
            runCatching { sourceManager.plugins.available() }
                .onSuccess { list -> _state.update { it.copy(loadingIndex = false, available = list) } }
                .onFailure { e -> _state.update { it.copy(loadingIndex = false, indexError = e.message ?: "unknown") } }
        }
    }

    fun install(plugin: LnReaderPlugin) {
        _state.update { it.copy(busy = it.busy + plugin.id, message = null) }
        screenModelScope.launch {
            runCatching { sourceManager.installPlugin(plugin) }
                .onFailure { e -> _state.update { it.copy(message = "${plugin.name}: ${e.message ?: "install failed"}") } }
            _state.update { it.copy(busy = it.busy - plugin.id) }
        }
    }

    fun uninstall(source: JsNovelSource) {
        sourceManager.uninstallPlugin(source)
    }

    data class State(
        val available: List<LnReaderPlugin> = emptyList(),
        val loadingIndex: Boolean = true,
        val indexError: String? = null,
        val busy: Set<String> = emptySet(),
        val message: String? = null,
    )
}
