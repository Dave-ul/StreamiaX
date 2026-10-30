package eu.kanade.tachiyomi.ui.novel

import android.app.Application
import androidx.core.text.HtmlCompat
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.novelsource.model.SNovelChapter
import eu.kanade.tachiyomi.source.novel.NovelSourceManager
import eu.kanade.tachiyomi.ui.novel.reader.NovelReaderPreferences
import eu.kanade.tachiyomi.ui.novel.reader.NovelSpeech
import eu.kanade.tachiyomi.ui.novel.reader.NovelTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.novel.repository.NovelRepository
import tachiyomi.domain.items.novelchapter.model.NovelChapterUpdate
import tachiyomi.domain.items.novelchapter.repository.NovelChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date
import java.util.Locale

class NovelReaderScreenModel(
    private val chapterId: Long,
    private val autoSpeak: Boolean,
    val preferences: NovelReaderPreferences = NovelReaderPreferences(Injekt.get()),
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
    private val chapterRepository: NovelChapterRepository = Injekt.get(),
    private val translator: NovelTranslator = NovelTranslator(),
) : ScreenModel {

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val openedAt = System.currentTimeMillis()

    private val speech = NovelSpeech(
        context = Injekt.get<Application>(),
        onChapterEnd = ::onSpeechChapterEnd,
        onUnavailable = { _state.update { it.copy(speechUnavailable = true) } },
    )
    val speaking = speech.speaking

    /** Chapters to open and keep reading aloud: a one-shot event, not state. */
    private val _speakNext = Channel<Long>(Channel.CONFLATED)
    val speakNext = _speakNext.receiveAsFlow()

    private var translateJob: Job? = null

    /** See [onPositionShown]. */
    private var shownParagraph: Int? = null

    init {
        load()
    }

    private fun load() {
        screenModelScope.launch {
            runCatching {
                val chapter = chapterRepository.getChapterById(chapterId)
                    ?: error("Chapter $chapterId not found")
                val novel = novelRepository.getNovelById(chapter.novelId)
                val source = sourceManager.get(novel.source)
                    ?: error("Source ${novel.source} not available")

                val html = source.getChapterText(
                    SNovelChapter.create().also {
                        it.url = chapter.url
                        it.name = chapter.name
                    },
                )
                val paragraphs = withContext(Dispatchers.Default) { htmlToParagraphs(html) }
                // A blank chapter is a broken page: shown as an error, so it is never marked read.
                check(paragraphs.isNotEmpty()) { "${source.name} returned no text for ${chapter.name}" }
                val siblings = chapterRepository.getChapterByNovelId(chapter.novelId).sortedBy { it.sourceOrder }
                val index = siblings.indexOfFirst { it.id == chapterId }
                _state.update {
                    it.copy(
                        loading = false,
                        chapterName = chapter.name,
                        paragraphs = paragraphs,
                        sourceLanguage = source.lang,
                        savedParagraph = chapter.lastPosition.toInt(),
                        previousChapterId = siblings.getOrNull(index - 1)?.id,
                        nextChapterId = siblings.getOrNull(index + 1)?.id,
                    )
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.message ?: "unknown") }
                return@launch
            }
            // Shows up in History right away; the reading time is added when leaving. Outside the
            // load above: a failed history write must not hide a chapter that loaded fine.
            persist("history") { chapterRepository.upsertHistory(chapterId, readAt = Date(), sessionReadDuration = 0) }
            if (preferences.translate().get()) {
                translateJob = launchTranslation()
                translateJob?.join()
            }
            // Speech carried over from the previous chapter starts this one from the top.
            if (autoSpeak) startSpeech(0)
        }
    }

    /**
     * The paragraph on screen, reported on every scroll or page turn. [savePosition] only follows
     * after a debounce, so leaving within it would lose the last move: [onDispose] writes this one.
     */
    fun onPositionShown(paragraph: Int) {
        shownParagraph = paragraph
    }

    fun savePosition(paragraph: Int) {
        // Kept in state too, so switching between scrolling and pages returns to this spot.
        _state.update { it.copy(savedParagraph = paragraph) }
        persist("position") {
            chapterRepository.update(NovelChapterUpdate(id = chapterId, lastPosition = paragraph.toLong()))
        }
    }

    fun markRead() {
        persist("read state") { chapterRepository.update(NovelChapterUpdate(id = chapterId, read = true)) }
    }

    /** Progress writes: a database error is logged rather than crashing the reader. */
    private fun persist(what: String, block: suspend () -> Unit) {
        screenModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Could not save the $what of chapter $chapterId" }
            }
        }
    }

    // ---- Translation ----

    fun setTranslation(enabled: Boolean) {
        preferences.translate().set(enabled)
        speech.stop()
        translateJob?.cancel()
        // While loading, load() starts the translation once the text is there.
        if (_state.value.loading) return
        if (enabled) {
            translateJob = launchTranslation()
        } else {
            _state.update { it.copy(translated = null, translating = false, translationError = null) }
        }
    }

    /** Re-translates after the target language changed. */
    fun setTranslationLanguage(language: String) {
        preferences.translateTo().set(language)
        if (preferences.translate().get()) setTranslation(true)
    }

    private fun launchTranslation(): Job = screenModelScope.launch {
        // Back to the original until the new translation arrives, so text and speech language agree.
        _state.update { it.copy(translated = null, translating = true, translationError = null) }
        runCatching { translator.translate(_state.value.paragraphs, translationTarget()) }
            .onSuccess { translated -> _state.update { it.copy(translated = translated, translating = false) } }
            .onFailure { e ->
                // Turning translation off cancels this job: that is not an error to show.
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Chapter translation failed" }
                _state.update { it.copy(translating = false, translationError = e.message ?: "unknown") }
            }
    }

    fun translationTarget(): String = preferences.translateTo().get().ifEmpty { Locale.getDefault().toLanguageTag() }

    // ---- Text to speech ----

    fun startSpeech(fromParagraph: Int) {
        val state = _state.value
        if (state.loading || state.error != null) return
        _state.update { it.copy(speechUnavailable = false) }
        val language = if (state.translated != null) translationTarget() else state.sourceLanguage
        val locale = language?.takeIf { it.isNotBlank() && it !in NON_LANGUAGES }?.let(Locale::forLanguageTag)
        speech.speak(
            paragraphs = state.text,
            from = fromParagraph,
            locale = locale,
            rate = preferences.speechRate().get(),
            pitch = preferences.speechPitch().get(),
        )
    }

    fun stopSpeech() = speech.stop()

    private fun onSpeechChapterEnd() {
        val next = _state.value.nextChapterId?.takeIf { preferences.speechNextChapter().get() }
        screenModelScope.launch {
            // Written before moving on: leaving the screen cancels this scope. Speech goes on to the
            // next chapter even if the write fails.
            try {
                chapterRepository.update(NovelChapterUpdate(id = chapterId, read = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Could not mark chapter $chapterId read" }
            }
            next?.let { _speakNext.send(it) }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onDispose() {
        speech.shutdown()
        val state = _state.value
        if (state.loading || state.error != null) return
        val duration = System.currentTimeMillis() - openedAt
        val position = (shownParagraph ?: state.savedParagraph).toLong()
        // screenModelScope is being cancelled, so the last writes go through GlobalScope (launchIO).
        // Each write is tried on its own: a failed position must not cost the reading time.
        launchIO {
            runCatching { chapterRepository.update(NovelChapterUpdate(id = chapterId, lastPosition = position)) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Could not save the position of chapter $chapterId" } }
            runCatching { chapterRepository.upsertHistory(chapterId, readAt = Date(), sessionReadDuration = duration) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Could not save the history of chapter $chapterId" } }
        }
    }

    /** Blank lines separate paragraphs; `<p>` and `<div>` become blank lines in legacy mode. */
    private fun htmlToParagraphs(html: String): List<String> =
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    data class State(
        val loading: Boolean = true,
        val chapterName: String = "",
        val paragraphs: List<String> = emptyList(),
        val translated: List<String>? = null,
        val translating: Boolean = false,
        val translationError: String? = null,
        val sourceLanguage: String? = null,
        /** Last saved reading position (paragraph index). */
        val savedParagraph: Int = 0,
        val previousChapterId: Long? = null,
        val nextChapterId: Long? = null,
        val speechUnavailable: Boolean = false,
        val error: String? = null,
    ) {
        /** What the reader shows and speaks: the translation when there is one. */
        val text: List<String> get() = translated ?: paragraphs
    }

    private companion object {
        // lnreader plugins use these for sources that are not in one language.
        val NON_LANGUAGES = setOf("all", "multi", "other")
    }
}
