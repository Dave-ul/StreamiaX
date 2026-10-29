package eu.kanade.tachiyomi.ui.player.subtitles

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
    fun `episode numbers match only as whole numbers`() {
        assertTrue(OnlineSubtitles.mentionsEpisode("Frieren.S01E03.srt", 3))
        assertTrue(OnlineSubtitles.mentionsEpisode("[Sub] Frieren - 03 [1080p].ass", 3))
        assertFalse(OnlineSubtitles.mentionsEpisode("Frieren.S01E13.srt", 3))
        assertFalse(OnlineSubtitles.mentionsEpisode("Frieren.S01E30.srt", 3))
    }
}
