package eu.kanade.tachiyomi.source.anime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StremioAnimeSourceTest {

    private val json = StremioAnimeSource.json

    @Test
    fun `torrent stream becomes a magnet with trackers and a 1-based file index`() {
        val stream = json.decodeFromString<StreamResponse>(
            """
            {"streams":[{"name":"Torrentio\n1080p","title":"Show.S02E03.mkv\n👤 42",
              "infoHash":"abc123","fileIdx":4,
              "sources":["tracker:udp://tracker.example:1337/announce","dht:abc123"],
              "behaviorHints":{"filename":"Show S02E03.mkv"}}]}
            """.trimIndent(),
        ).streams.single()

        assertEquals(
            "magnet:?xt=urn:btih:abc123&dn=Show%20S02E03.mkv" +
                "&tr=udp%3A%2F%2Ftracker.example%3A1337%2Fannounce&index=5",
            stream.playableUrl(),
        )
        assertEquals("Torrentio 1080p · Show.S02E03.mkv 👤 42", stream.displayTitle())
    }

    @Test
    fun `direct url wins and a stream without url or hash is skipped`() {
        assertEquals("https://cdn/x.mp4", StreamInfo(url = "https://cdn/x.mp4", infoHash = "h").playableUrl())
        assertNull(StreamInfo(name = "external only").playableUrl())
    }

    @Test
    fun `episodes are numbered per season, named, and specials dropped`() {
        val meta = json.decodeFromString<MetaResponse>(
            """
            {"meta":{"id":"tt1","name":"Show","videos":[
              {"id":"tt1:0:1","name":"Special","season":0,"episode":1},
              {"id":"tt1:1:1","name":"Pilot","season":1,"episode":1,"released":"2020-01-01T00:00:00.000Z"},
              {"id":"tt1:2:1","title":"Return","season":2,"episode":1}
            ]}}
            """.trimIndent(),
        ).meta

        val episodes = meta.toEpisodes("series")

        assertEquals(listOf("series/tt1:2:1", "series/tt1:1:1"), episodes.map { it.url })
        assertEquals(listOf(2001f, 1001f), episodes.map { it.episode_number })
        assertEquals(listOf("S2E1 · Return", "S1E1 · Pilot"), episodes.map { it.name })
        assertEquals(1577836800000L, episodes[1].date_upload)
    }

    @Test
    fun `search extra is uri-encoded`() {
        assertEquals(
            "https://c/catalog/movie/top/search=the%20matrix%2Freloaded&skip=100.json",
            catalogUrl("https://c", "movie", "top", "the matrix/reloaded", skip = 100),
        )
        assertEquals("https://c/catalog/movie/top.json", catalogUrl("https://c", "movie", "top", null, 0))
    }
}
