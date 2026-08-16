package eu.kanade.tachiyomi.source.novel

import eu.kanade.tachiyomi.novelsource.NovelSource
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
 * Only built-in sources exist for now, so there is no extension manager, stub source or download
 * manager wiring here; JS plugin sources join [builtInSources] once the lnreader plugin host lands.
 */
class NovelSourceManager(
    private val handler: NovelDatabaseHandler,
) {

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private val sourcesMap = ConcurrentHashMap<Long, NovelSource>(
        builtInSources().associateBy { it.id },
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
