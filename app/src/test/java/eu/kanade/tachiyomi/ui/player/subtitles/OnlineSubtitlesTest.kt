package eu.kanade.tachiyomi.ui.player.subtitles

import eu.kanade.tachiyomi.ui.player.subtitles.OnlineSubtitles.Provider
import eu.kanade.tachiyomi.ui.player.subtitles.OnlineSubtitles.Result
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile

class OnlineSubtitlesTest {

    @Test
    fun `hash matches the reference OpenSubtitles algorithm`() {
        // Expected value computed with the reference Python implementation on the same bytes.
        val file = File.createTempFile("subhash", ".bin").apply { deleteOnExit() }
        file.writeBytes(ByteArray(200_000) { ((it * 31 + 7) and 0xFF).toByte() })

        val result = RandomAccessFile(file, "r").channel.use { OnlineSubtitles.openSubtitlesHash(it) }

        assertEquals(200_000L to "5f9fe02060a3cd40", result)
    }

    @Test
    fun `files smaller than two chunks are not hashed`() {
        val file = File.createTempFile("subhash", ".bin").apply { deleteOnExit() }
        file.writeBytes(ByteArray(1000))

        assertNull(RandomAccessFile(file, "r").channel.use { OnlineSubtitles.openSubtitlesHash(it) })
    }

    @Test
    fun `season is read from common anime title forms`() {
        assertEquals(2, OnlineSubtitles.seasonFromTitle("Sousou no Frieren Season 2"))
        assertEquals(3, OnlineSubtitles.seasonFromTitle("Mushoku Tensei S3"))
        assertEquals(2, OnlineSubtitles.seasonFromTitle("Oshi no Ko 2nd Season"))
        assertNull(OnlineSubtitles.seasonFromTitle("Frieren"))
        assertNull(OnlineSubtitles.seasonFromTitle("Season 1"))
    }

    @Test
    fun `default language uses OpenSubtitles bibliographic codes`() {
        assertEquals("ita", OnlineSubtitles.defaultLanguage("it,en"))
        assertEquals("fre", OnlineSubtitles.defaultLanguage("fr"))
        assertEquals("ger", OnlineSubtitles.defaultLanguage(" de "))
    }

    @Test
    fun `episode numbers match only after an episode marker`() {
        assertTrue(OnlineSubtitles.mentionsEpisode("Frieren.S01E03.srt", 3))
        assertTrue(OnlineSubtitles.mentionsEpisode("Frieren.S01E01.srt", 1))
        assertTrue(OnlineSubtitles.mentionsEpisode("[Sub] Frieren - 03 [1080p].ass", 3))
        assertTrue(OnlineSubtitles.mentionsEpisode("Frieren Episode 3.srt", 3))
        assertTrue(OnlineSubtitles.mentionsEpisode("Frieren.Ep.03.srt", 3))
        assertFalse(OnlineSubtitles.mentionsEpisode("Frieren.S01E13.srt", 3))
        assertFalse(OnlineSubtitles.mentionsEpisode("Frieren.S01E30.srt", 3))
        // The season number must not count as the episode.
        assertFalse(OnlineSubtitles.mentionsEpisode("Frieren.S01E05.srt", 1))
        assertFalse(OnlineSubtitles.mentionsEpisode("Show.S02E07.srt", 2))
    }

    @Test
    fun `ranking puts hash and episode matches first and drops the rest`() {
        val plain = result("a", downloads = 100)
        val episode = result("b", downloads = 5, episodeMatch = true)
        val hash = result("c", downloads = 1, hashMatch = true)

        assertEquals(listOf(hash, episode), OnlineSubtitles.rank(listOf(plain, episode, hash), episode = 3))
        assertEquals(listOf(hash, episode, plain), OnlineSubtitles.rank(listOf(plain, episode, hash), episode = null))
    }

    @Test
    fun `ranking keeps everything by downloads when nothing matches`() {
        val few = result("a", downloads = 1)
        val many = result("b", downloads = 50)

        assertEquals(listOf(many, few), OnlineSubtitles.rank(listOf(few, many), episode = 3))
    }

    @Test
    fun `legacy rows are parsed and rows without a link are skipped`() {
        val row = json(
            """{"IDSubtitleFile":"42","SubFileName":"Show.S01E03.srt","SubDownloadLink":"https://x/42.gz",
            "SubLanguageID":"ita","LanguageName":null,"SubFormat":"SRT","SubDownloadsCnt":"abc",
            "SubHearingImpaired":"1","SeriesEpisode":"3"}""",
        )

        val parsed = OnlineSubtitles.parseLegacyRow(row, hash = false, episode = 3)

        assertEquals("ita", parsed?.language)
        assertEquals("srt", parsed?.format)
        assertEquals(0, parsed?.downloads)
        assertEquals(true, parsed?.hearingImpaired)
        assertEquals(true, parsed?.episodeMatch)
        assertEquals(Provider.OPENSUBTITLES, parsed?.provider)
        assertNull(OnlineSubtitles.parseLegacyRow(json("""{"IDSubtitleFile":"1","SubFileName":"a.srt"}"""), false, 3))
    }

    @Test
    fun `stremio rows keep the requested language and a known format`() {
        val italian = json("""{"id":"9","url":"https://s/9","lang":"ita","subtitleFileName":"show.xyz"}""")
        val chinese = json("""{"id":"8","url":"https://s/8","lang":"zho"}""")

        val parsed = OnlineSubtitles.parseStremioRow(italian, "ita")

        assertEquals("stremio-9", parsed?.id)
        assertEquals("srt", parsed?.format)
        assertEquals(Provider.STREMIO, parsed?.provider)
        assertNull(OnlineSubtitles.parseStremioRow(chinese, "ita"))
    }

    @Test
    fun `cache file names cannot leave the cache folder`() {
        val hostile = result("../../etc/x", format = "srt")

        val name = OnlineSubtitles.cacheFileName(hostile)

        assertFalse(name.contains('/'))
        assertEquals("______etc_x.srt", name)
        assertEquals("1.ass", OnlineSubtitles.cacheFileName(result("1", format = "ssa")))
    }

    @Test
    fun `a different season is a different video`() {
        val s1 = OnlineSubtitles.Query("Show", "ita", season = 1, episode = 3, imdbId = "tt1")

        assertTrue(s1.sameVideoAs(s1.copy(title = "Edited", language = "eng")))
        assertFalse(s1.sameVideoAs(s1.copy(season = 2)))
        assertFalse(s1.sameVideoAs(s1.copy(episode = 4)))
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun result(
        id: String,
        downloads: Int = 0,
        hashMatch: Boolean = false,
        episodeMatch: Boolean = false,
        format: String = "srt",
    ) = Result(
        id = id,
        name = id,
        language = "ita",
        format = format,
        link = "https://example.com/$id",
        downloads = downloads,
        hearingImpaired = false,
        hashMatch = hashMatch,
        episodeMatch = episodeMatch,
        provider = Provider.OPENSUBTITLES,
    )
}
