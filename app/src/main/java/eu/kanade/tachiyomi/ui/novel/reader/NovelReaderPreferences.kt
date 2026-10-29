package eu.kanade.tachiyomi.ui.novel.reader

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * Novel reader settings. Theme presets and the line height, keep-screen-on, page mode and
 * speech defaults follow lnreader (src/hooks/persisted/useSettings.ts,
 * src/utils/constants/readerConstants.ts). Text size (18) and side margins (20) keep
 * StreamiaX's own defaults; lnreader uses 16 and 16.
 */
class NovelReaderPreferences(private val store: PreferenceStore) {

    fun fontSize() = store.getInt("novel_reader_font_size", 18)

    fun lineHeight() = store.getFloat("novel_reader_line_height", 1.5f)

    fun sidePadding() = store.getInt("novel_reader_side_padding", 20)

    fun justify() = store.getBoolean("novel_reader_justify", false)

    fun font() = store.getEnum("novel_reader_font", ReaderFont.DEFAULT)

    fun theme() = store.getEnum("novel_reader_theme", ReaderTheme.APP)

    fun pagedMode() = store.getBoolean("novel_reader_paged", false)

    fun volumeKeys() = store.getBoolean("novel_reader_volume_keys", false)

    fun keepScreenOn() = store.getBoolean("novel_reader_keep_screen_on", true)

    fun speechRate() = store.getFloat("novel_reader_tts_rate", 1f)

    fun speechPitch() = store.getFloat("novel_reader_tts_pitch", 1f)

    fun speechNextChapter() = store.getBoolean("novel_reader_tts_next_chapter", false)

    fun translate() = store.getBoolean("novel_reader_translate", false)

    /** BCP 47 target language; empty means the device language. */
    fun translateTo() = store.getString("novel_reader_translate_to", "")
}

enum class ReaderFont {
    DEFAULT,
    SERIF,
    SANS_SERIF,
    MONOSPACE,
}

/** Background and text colours as ARGB; [APP] keeps the app's own colours. */
enum class ReaderTheme(val background: Long?, val text: Long?) {
    APP(null, null),
    LIGHT(0xFFF5F5FA, 0xFF111111),
    SEPIA(0xFFF7DFC6, 0xFF593100),
    MINT(0xFFDCE5E2, 0xFF000000),
    DARK(0xFF292832, 0xFFCCCCCC),
    BLACK(0xFF000000, 0xB3FFFFFF),
}
