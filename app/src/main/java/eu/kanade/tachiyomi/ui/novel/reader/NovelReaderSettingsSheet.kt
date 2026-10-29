package eu.kanade.tachiyomi.ui.novel.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale
import kotlin.math.roundToInt

/** Text, theme, reading, speech and translation settings, grouped like lnreader's reader sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelReaderSettingsSheet(
    preferences: NovelReaderPreferences,
    translationTarget: String,
    onTranslationLanguage: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Section(AYMR.strings.novel_reader_section_text)
            IntSlider(preferences.fontSize(), AYMR.strings.novel_reader_font_size, 12..32)
            FloatSlider(preferences.lineHeight(), AYMR.strings.novel_reader_line_height, 1.0f..2.4f, steps = 13)
            IntSlider(preferences.sidePadding(), AYMR.strings.novel_reader_side_padding, 0..48, step = 4)
            SwitchRow(preferences.justify(), AYMR.strings.novel_reader_justify)
            ChoiceRow(
                preference = preferences.font(),
                values = ReaderFont.entries,
                label = { stringResource(it.title) },
            )

            Section(AYMR.strings.novel_reader_section_theme)
            ThemeRow(preferences.theme())

            Section(AYMR.strings.novel_reader_section_reading)
            SwitchRow(preferences.pagedMode(), AYMR.strings.novel_reader_paged)
            SwitchRow(preferences.volumeKeys(), AYMR.strings.novel_reader_volume_keys)
            SwitchRow(preferences.keepScreenOn(), AYMR.strings.novel_reader_keep_screen_on)

            Section(AYMR.strings.novel_reader_section_speech)
            FloatSlider(preferences.speechRate(), AYMR.strings.novel_reader_speech_rate, 0.5f..2.5f, steps = 19)
            FloatSlider(preferences.speechPitch(), AYMR.strings.novel_reader_speech_pitch, 0.5f..2.0f, steps = 14)
            SwitchRow(preferences.speechNextChapter(), AYMR.strings.novel_reader_speech_next_chapter)

            Section(AYMR.strings.novel_reader_section_translation)
            TranslationLanguageRow(translationTarget, onTranslationLanguage)
        }
    }
}

private val ReaderFont.title: StringResource
    get() = when (this) {
        ReaderFont.DEFAULT -> AYMR.strings.novel_reader_font_default
        ReaderFont.SERIF -> AYMR.strings.novel_reader_font_serif
        ReaderFont.SANS_SERIF -> AYMR.strings.novel_reader_font_sans
        ReaderFont.MONOSPACE -> AYMR.strings.novel_reader_font_mono
    }

/** Translation targets offered in the menu; the device language is added first. */
private val TRANSLATION_LANGUAGES = listOf(
    "it", "en", "es", "fr", "de", "pt", "ru", "ja", "ko", "zh-CN", "ar", "id", "tr", "pl", "vi",
)

@Composable
private fun Section(title: StringResource) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun IntSlider(preference: Preference<Int>, title: StringResource, range: IntRange, step: Int = 1) {
    val value by preference.collectAsState()
    Text("${stringResource(title)}: $value")
    Slider(
        value = value.toFloat(),
        onValueChange = { preference.set((it / step).roundToInt() * step) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first) / step - 1,
    )
}

@Composable
private fun FloatSlider(
    preference: Preference<Float>,
    title: StringResource,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    val value by preference.collectAsState()
    Text("${stringResource(title)}: ${"%.1f".format(value)}")
    Slider(
        value = value,
        // Round to one decimal so the stored value matches the label.
        onValueChange = { preference.set(Math.round(it * 10) / 10f) },
        valueRange = range,
        steps = steps,
    )
}

@Composable
private fun SwitchRow(preference: Preference<Boolean>, title: StringResource) {
    val checked by preference.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { preference.set(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(title), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = preference::set)
    }
}

@Composable
private fun <T> ChoiceRow(preference: Preference<T>, values: List<T>, label: @Composable (T) -> String) {
    val selected by preference.collectAsState()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { preference.set(value) },
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
private fun ThemeRow(preference: Preference<ReaderTheme>) {
    val selected by preference.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ReaderTheme.entries.forEach { theme ->
            val background = theme.background?.let { Color(it) } ?: MaterialTheme.colorScheme.background
            val text = theme.text?.let { Color(it) } ?: MaterialTheme.colorScheme.onBackground
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(background, CircleShape)
                    .border(
                        BorderStroke(
                            width = if (theme == selected) 3.dp else 1.dp,
                            color = if (theme == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                text.copy(alpha = 0.4f)
                            },
                        ),
                        CircleShape,
                    )
                    .clickable { preference.set(theme) },
                contentAlignment = Alignment.Center,
            ) {
                Text("A", color = text)
            }
        }
    }
}

@Composable
private fun TranslationLanguageRow(current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val device = Locale.getDefault().toLanguageTag()
    val options = (listOf(device) + TRANSLATION_LANGUAGES).distinctBy { it.substringBefore('-') }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(AYMR.strings.novel_reader_translate_to), modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }) { Text(languageName(current)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(languageName(tag)) },
                        onClick = {
                            expanded = false
                            onSelect(tag)
                        },
                    )
                }
            }
        }
    }
}

private fun languageName(tag: String): String =
    Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it) }
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        .ifEmpty { tag }
