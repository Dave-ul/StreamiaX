package eu.kanade.tachiyomi.source.novel

import eu.kanade.tachiyomi.novelsource.NovelSource
import eu.kanade.tachiyomi.source.novel.js.JsNovelSource
import eu.kanade.tachiyomi.source.novel.js.LnReaderPlugin
import eu.kanade.tachiyomi.source.novel.js.LnReaderPlugins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.handlers.novel.NovelDatabaseHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of available [NovelSource]s, the novel counterpart of `AndroidAnimeSourceManager`.
 *
 * Holds the built-in sources plus one [JsNovelSource] per installed lnreader plugin; there is no
 * stub source or download manager wiring here.
 */
class NovelSourceManager(
    private val handler: NovelDatabaseHandler,
) {

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    val plugins = LnReaderPlugins()

    private val sourcesMap = ConcurrentHashMap<Long, NovelSource>(
        (builtInSources() + plugins.installed().map(plugins::source)).associateBy { it.id },
    )

    private val _sources = MutableStateFlow(sourcesMap.values.toList())
    val sources: StateFlow<List<NovelSource>> = _sources.asStateFlow()

    init {
        scope.launch { registerSources(sourcesMap.values) }
    }

    fun get(sourceKey: Long): NovelSource? = sourcesMap[sourceKey]

    /** Name shown for a source that is no longer available, e.g. an uninstalled plugin. */
    suspend fun getNameOrStub(sourceKey: Long): String {
        sourcesMap[sourceKey]?.let { return it.name }
        return handler.awaitOneOrNull { novelsourcesQueries.findOne(sourceKey) }?.name
            ?: sourceKey.toString()
    }

    /** Installs or updates [plugin] and makes it available as a source right away. */
    suspend fun installPlugin(plugin: LnReaderPlugin) {
        val source = plugins.install(plugin)
        (sourcesMap.put(source.id, source) as? JsNovelSource)?.close()
        _sources.value = sourcesMap.values.toList()
        registerSources(listOf(source))
    }

    fun uninstallPlugin(source: JsNovelSource) {
        plugins.uninstall(source.plugin.id)
        sourcesMap.remove(source.id)
        source.close()
        _sources.value = sourcesMap.values.toList()
    }

    private suspend fun registerSources(sources: Collection<NovelSource>) {
        try {
            handler.await(inTransaction = true) {
                sources.forEach { novelsourcesQueries.upsert(it.id, it.lang, it.name) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    private companion object {
        fun builtInSources(): List<NovelSource> = listOf(GutenbergNovelSource())
    }
}
