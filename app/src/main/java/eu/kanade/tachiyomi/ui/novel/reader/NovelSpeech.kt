package eu.kanade.tachiyomi.ui.novel.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.Locale

/**
 * Reads a chapter aloud one paragraph at a time with the system text-to-speech engine,
 * like lnreader's TTS (rate, pitch, optional jump to the next chapter at the end).
 */
class NovelSpeech(
    context: Context,
    private val onChapterEnd: () -> Unit,
) {

    /** Paragraph being read, or null when idle. */
    private val _speaking = MutableStateFlow<Int?>(null)
    val speaking = _speaking.asStateFlow()

    private var ready = false
    private var pending: (() -> Unit)? = null

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            pending?.invoke()
        } else {
            logcat(LogPriority.ERROR) { "Text-to-speech init failed: $status" }
            _speaking.value = null
        }
        pending = null
    }

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    _speaking.value = utteranceId.substringBefore(':').toIntOrNull()
                }

                override fun onDone(utteranceId: String) {
                    if (utteranceId.endsWith(LAST_MARK)) {
                        _speaking.value = null
                        onChapterEnd()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    logcat(LogPriority.WARN) { "Text-to-speech failed on $utteranceId" }
                }
            },
        )
    }

    /** Queues [paragraphs] from [from] to the end of the chapter. */
    fun speak(paragraphs: List<String>, from: Int, locale: Locale?, rate: Float, pitch: Float) {
        val start = from.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
        _speaking.value = start
        val action = {
            tts.stop()
            locale?.takeIf { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }?.let(tts::setLanguage)
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
            val maxLength = TextToSpeech.getMaxSpeechInputLength()
            for (index in start until paragraphs.size) {
                val parts = splitForSpeech(paragraphs[index], maxLength)
                parts.forEachIndexed { part, text ->
                    val last = index == paragraphs.lastIndex && part == parts.lastIndex
                    val id = "$index:$part" + if (last) LAST_MARK else ""
                    tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
                }
            }
        }
        if (ready) action() else pending = action
    }

    fun stop() {
        pending = null
        tts.stop()
        _speaking.value = null
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    companion object {
        private const val LAST_MARK = ":end"
        private val SENTENCE_ENDS = listOf(". ", "! ", "? ", "。", "\n")

        /** Splits [text] at sentence ends, then spaces, so each part fits the engine's limit. */
        internal fun splitForSpeech(text: String, maxLength: Int): List<String> {
            if (text.length <= maxLength) return listOf(text)
            val parts = mutableListOf<String>()
            var rest = text
            while (rest.length > maxLength) {
                val window = rest.substring(0, maxLength)
                val sentenceEnd = SENTENCE_ENDS.maxOf { end ->
                    window.lastIndexOf(end).let { if (it < 0) -1 else it + end.length }
                }
                val cut = sentenceEnd.takeIf { it > 0 }
                    ?: window.lastIndexOf(' ').takeIf { it > 0 }
                    ?: maxLength
                parts += rest.substring(0, cut).trim()
                rest = rest.substring(cut)
            }
            if (rest.isNotBlank()) parts += rest.trim()
            return parts.filter(String::isNotEmpty)
        }
    }
}
