package eu.kanade.tachiyomi.ui.browse.novel.extension

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.browse.novel.NovelBrowseRow
import eu.kanade.presentation.browse.novel.NovelListHeader
import eu.kanade.presentation.browse.novel.languageName
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.source.novel.NovelSourceManager
import eu.kanade.tachiyomi.source.novel.js.JsNovelSource
import eu.kanade.tachiyomi.source.novel.js.LnReaderPlugin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** lnreader plugins: pending updates, installed, then available by language, like the extension tabs. */
@Composable
fun novelExtensionsTab(screenModel: NovelExtensionsScreenModel): TabContent {
    val state by screenModel.state.collectAsState()
    val installedPlugins by screenModel.installed.collectAsState()

    return TabContent(
        titleRes = AYMR.strings.label_novel_extensions,
        searchEnabled = true,
        searchQuery = state.query,
        onChangeSearchQuery = screenModel::search,
        content = { contentPadding, _ ->
            val query = state.query.orEmpty()
            val latest = state.available.associateBy { it.id }
            val installed = installedPlugins.filter { it.plugin.matches(query) }.sortedBy { it.name.lowercase() }
            val updates = installed.filter { source ->
                val latestVersion = latest[source.plugin.id]?.version
                latestVersion != null && latestVersion != source.plugin.version
            }
            val installedIds = installedPlugins.mapTo(HashSet()) { it.plugin.id }
            val available = state.available
                .filter { it.id !in installedIds && it.matches(query) }
                .sortedWith(compareBy({ it.lang }, { it.name.lowercase() }))
                .groupBy { it.lang }

            ScrollbarLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
                state.message?.let { message ->
                    item(key = "message") {
                        Text(
                            message,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (updates.isNotEmpty()) {
                    item(key = "header-updates") { NovelListHeader(stringResource(MR.strings.ext_updates_pending)) }
                    items(updates, key = { "update-${it.plugin.id}" }) { source ->
                        val update = latest.getValue(source.plugin.id)
                        PluginRow(update, subtitleVersion = "${source.plugin.version} → ${update.version}") {
                            if (update.id in state.busy) {
                                Busy()
                            } else {
                                TextButton(onClick = {
                                    screenModel.install(update)
                                }) { Text(stringResource(MR.strings.ext_update)) }
                            }
                        }
                    }
                }
                if (installed.isNotEmpty()) {
                    item(key = "header-installed") { NovelListHeader(stringResource(MR.strings.ext_installed)) }
                    items(installed, key = { "installed-${it.plugin.id}" }) { source ->
                        PluginRow(source.plugin, subtitleVersion = source.plugin.version) {
                            TextButton(onClick = { screenModel.uninstall(source) }) {
                                Text(stringResource(MR.strings.ext_uninstall))
                            }
                        }
                    }
                }
                when {
                    state.loadingIndex -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.indexError != null -> item(key = "index-error") {
                        Column(Modifier.padding(16.dp)) {
                            Text(state.indexError!!, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = screenModel::loadIndex) {
                                Text(stringResource(MR.strings.action_retry))
                            }
                        }
                    }
                    else -> available.forEach { (lang, plugins) ->
                        item(key = "header-$lang") { NovelListHeader(languageName(lang)) }
                        items(plugins, key = { "available-${it.id}" }) { plugin ->
                            PluginRow(plugin, subtitleVersion = plugin.version) {
                                if (plugin.id in state.busy) {
                                    Busy()
                                } else {
                                    TextButton(onClick = { screenModel.install(plugin) }) {
                                        Text(stringResource(MR.strings.ext_install))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PluginRow(plugin: LnReaderPlugin, subtitleVersion: String, action: @Composable () -> Unit) {
    NovelBrowseRow(
        title = plugin.name,
        subtitle = "${languageName(plugin.lang)} · v$subtitleVersion",
        iconUrl = plugin.iconUrl,
        action = { action() },
    )
}

@Composable
private fun Busy() {
    Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

private fun LnReaderPlugin.matches(query: String) =
    query.isBlank() || name.contains(query, ignoreCase = true) || lang.contains(query, ignoreCase = true)

class NovelExtensionsScreenModel(
    private val sourceManager: NovelSourceManager = Injekt.get(),
) : ScreenModel {

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /** Installed plugin sources, kept apart from built-in sources such as Gutenberg. */
    val installed = MutableStateFlow(emptyList<JsNovelSource>())

    init {
        screenModelScope.launch {
            sourceManager.sources.collect { sources -> installed.value = sources.filterIsInstance<JsNovelSource>() }
        }
        loadIndex()
    }

    fun search(query: String?) {
        _state.update { it.copy(query = query) }
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
                .onFailure { e ->
                    _state.update { it.copy(message = "${plugin.name}: ${e.message ?: "install failed"}") }
                }
            _state.update { it.copy(busy = it.busy - plugin.id) }
        }
    }

    fun uninstall(source: JsNovelSource) {
        sourceManager.uninstallPlugin(source)
    }

    data class State(
        val query: String? = null,
        val available: List<LnReaderPlugin> = emptyList(),
        val loadingIndex: Boolean = true,
        val indexError: String? = null,
        val busy: Set<String> = emptySet(),
        val message: String? = null,
    )
}
