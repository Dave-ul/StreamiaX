package eu.kanade.tachiyomi.ui.player.subtitles

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * State behind the online subtitle sheet. The player supplies the starting [OnlineSubtitles.Query]
 * when the sheet opens and loads the downloaded file through [onDownloaded].
 */
class OnlineSubtitlesController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val cacheDir: File,
    private val onDownloaded: (file: File, title: String) -> Unit,
    private val subtitles: OnlineSubtitles = OnlineSubtitles(),
) {

    data class State(
        val query: OnlineSubtitles.Query? = null,
        val searching: Boolean = false,
        val results: List<OnlineSubtitles.Result> = emptyList(),
        val searched: Boolean = false,
        val error: String? = null,
        val downloadingId: String? = null,
        val addedIds: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var searchJob: Job? = null

    /** Starts from [initial] the first time, or when the video, season or episode changed since. */
    fun open(initial: OnlineSubtitles.Query) {
        if (_state.value.query?.sameVideoAs(initial) == true) return
        _state.value = State(query = initial)
        search(initial.title, initial.language)
    }

    fun search(title: String, language: String) {
        val query = _state.value.query?.copy(title = title, language = language) ?: return
        searchJob?.cancel()
        _state.update { it.copy(query = query, searching = true, error = null) }
        searchJob = scope.launch {
            catching { subtitles.search(context, query) }
                .onSuccess { results ->
                    _state.update { it.copy(searching = false, searched = true, results = results) }
                }
                .onFailure { e ->
                    logcat(LogPriority.ERROR, e) { "Online subtitle search failed" }
                    _state.update {
                        it.copy(searching = false, searched = true, results = emptyList(), error = e.message)
                    }
                }
        }
    }

    fun add(result: OnlineSubtitles.Result) {
        // Calls come from the UI thread, so check-then-set cannot race.
        if (_state.value.downloadingId != null) return
        _state.update { it.copy(downloadingId = result.id, error = null) }
        scope.launch {
            catching { subtitles.download(result, cacheDir) }
                .onSuccess { file ->
                    onDownloaded(file, "${result.name} (${result.provider.label})")
                    _state.update { it.copy(downloadingId = null, addedIds = it.addedIds + result.id) }
                }
                .onFailure { e ->
                    logcat(LogPriority.ERROR, e) { "Online subtitle download failed" }
                    _state.update { it.copy(downloadingId = null, error = e.message) }
                }
        }
    }
}
