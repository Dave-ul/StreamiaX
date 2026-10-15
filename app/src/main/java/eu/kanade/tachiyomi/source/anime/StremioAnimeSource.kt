package eu.kanade.tachiyomi.source.anime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.time.Instant

/**
 * Built-in source that bridges the Stremio add-on HTTP protocol into Aniyomi.
 *
 * Catalog and metadata come from Cinemeta, streams from Torrentio and subtitles from
 * OpenSubtitles v3. Torrent streams (`infoHash`) become magnet links, which the player hands
 * to the embedded torrent server (Settings → Player → Torrent must be enabled).
 *
 * Stremio entry URLs are stored as `"<type>/<id>"` (e.g. `movie/tt0111161`,
 * `series/tt0944947:1:1`).
 */
class StremioAnimeSource : AnimeHttpSource() {

    override val name = "Stremio"
    override val lang = "all"
    override val supportsLatest = false
    override val baseUrl = CATALOG_URL
    override val id = ID

    // ---- Catalog ----

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        return catalogPage("movie", "top", query = null, page = page)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)
        val movies = catalogPage("movie", "top", query, page).animes
        val series = catalogPage("series", "top", query, page).animes
        return AnimesPage(movies + series, hasNextPage = false)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)

    private suspend fun catalogPage(type: String, catalogId: String, query: String?, page: Int): AnimesPage {
        val url = catalogUrl(CATALOG_URL, type, catalogId, query, skip = (page - 1) * PAGE_SIZE)
        val metas = get<CatalogResponse>(url).metas
        return AnimesPage(
            animes = metas.map { it.toSAnime(type) },
            hasNextPage = query.isNullOrBlank() && metas.size >= PAGE_SIZE,
        )
    }

    // ---- Details + episodes (one meta request for both) ----

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        val (type, realId) = anime.url.parseEntryUrl()
        val meta = fetchMeta(type, realId)
        return SAnimeEpisodeUpdate(
            anime = if (fetchDetails) meta.toSAnime(type).apply { initialized = true } else anime,
            episodes = if (fetchEpisodes) meta.toEpisodes(type) else episodes,
        )
    }

    @Deprecated("Use getAnimeEpisodeUpdate")
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (type, realId) = anime.url.parseEntryUrl()
        return fetchMeta(type, realId).toSAnime(type).apply { initialized = true }
    }

    @Deprecated("Use getAnimeEpisodeUpdate")
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (type, realId) = anime.url.parseEntryUrl()
        return fetchMeta(type, realId).toEpisodes(type)
    }

    private suspend fun fetchMeta(type: String, realId: String): MetaPreview =
        get<MetaResponse>("$CATALOG_URL/meta/$type/$realId.json").meta

    // ---- Videos / Hosters ----

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = fetchStreams(episode.url).toHosterList()

    @Deprecated("Use getHosterList")
    override suspend fun getVideoList(episode: SEpisode): List<Video> = fetchStreams(episode.url)

    private suspend fun fetchStreams(episodeUrl: String): List<Video> = coroutineScope {
        val (type, realId) = episodeUrl.parseEntryUrl()
        val subtitles = async {
            // Subtitles are a nice-to-have: a failing subtitle add-on must not hide the streams.
            runCatching { get<SubtitlesResponse>("$SUBTITLES_URL/subtitles/$type/$realId.json").subtitles }
                .getOrDefault(emptyList())
                .map { Track(it.url, it.lang) }
        }
        val streams = get<StreamResponse>("$STREAM_URL/stream/$type/$realId.json").streams
        val tracks = subtitles.await()
        streams.mapNotNull { s ->
            val url = s.playableUrl() ?: return@mapNotNull null
            Video(
                videoUrl = url,
                videoTitle = s.displayTitle(),
                headers = s.behaviorHints?.proxyHeaders?.request?.toHeaders(),
                subtitleTracks = tracks,
            )
        }
    }

    // ---- Helpers ----

    private suspend inline fun <reified T> get(url: String): T {
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        return json.decodeFromString<T>(response.body.string())
    }

    private fun String.parseEntryUrl(): Pair<String, String> {
        val parts = removePrefix("/").split("/", limit = 2)
        return parts[0] to parts.getOrElse(1) { "" }
    }

    // Request/parse members are unused: the suspend API above is overridden directly.
    override fun popularAnimeRequest(page: Int): Request = unsupported()
    override fun popularAnimeParse(response: Response): AnimesPage = unsupported()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = unsupported()
    override fun searchAnimeParse(response: Response): AnimesPage = unsupported()
    override fun latestUpdatesRequest(page: Int): Request = unsupported()
    override fun latestUpdatesParse(response: Response): AnimesPage = unsupported()
    override fun animeDetailsParse(response: Response): SAnime = unsupported()
    override fun episodeListParse(response: Response): List<SEpisode> = unsupported()
    override fun episodeVideoParse(response: Response): SEpisode = unsupported()
    override fun seasonListParse(response: Response): List<SAnime> = unsupported()
    override fun hosterListParse(response: Response): List<Hoster> = unsupported()
    override fun videoListParse(response: Response): List<Video> = unsupported()
    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = unsupported()
    override fun videoUrlParse(response: Response): String = unsupported()

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("StremioAnimeSource uses the suspend API")

    companion object {
        const val ID = 6_900_000_000_000_000_001L
        private const val CATALOG_URL = "https://v3-cinemeta.strem.io"
        private const val STREAM_URL = "https://torrentio.strem.fun"
        private const val SUBTITLES_URL = "https://opensubtitles-v3.strem.io"
        private const val PAGE_SIZE = 100

        internal val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

// ---- Pure protocol mapping (unit-tested in StremioAnimeSourceTest) ----

/** `/catalog/{type}/{id}/{extra}.json`, with extra values URI-component encoded as Stremio does. */
internal fun catalogUrl(base: String, type: String, catalogId: String, query: String?, skip: Int): String {
    val extra = buildList {
        if (!query.isNullOrBlank()) add("search=${query.encodeUriComponent()}")
        if (skip > 0) add("skip=$skip")
    }.joinToString("&")
    val suffix = if (extra.isEmpty()) "" else "/$extra"
    return "$base/catalog/$type/$catalogId$suffix.json"
}

private fun String.encodeUriComponent(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

/**
 * Season-aware numbering, so S1E1 and S2E1 no longer collide: `season * 1000 + episode`.
 * Specials (season 0) are dropped.
 */
internal fun MetaPreview.toEpisodes(type: String): List<SEpisode> {
    if (type == "movie") {
        return listOf(
            SEpisode.create().apply {
                url = "movie/$id"
                name = this@toEpisodes.name
                episode_number = 1f
            },
        )
    }
    return videos.orEmpty()
        .filter { (it.season ?: 1) > 0 }
        .mapIndexed { idx, v ->
            val season = v.season ?: 1
            val episode = v.episode ?: (idx + 1)
            SEpisode.create().apply {
                url = "$type/${v.id}"
                val label = "S${season}E$episode"
                name = v.displayName?.let { "$label · $it" } ?: label
                episode_number = (season * 1000 + episode).toFloat()
                summary = v.overview ?: v.description
                preview_url = v.thumbnail
                date_upload = v.released?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            }
        }
        .sortedByDescending { it.episode_number }
}

/**
 * Direct URL when the add-on gives one, otherwise a magnet for `infoHash` streams.
 * Stremio's `fileIdx` is 0-based while the torrent server numbers files from 1.
 */
internal fun StreamInfo.playableUrl(): String? {
    url?.let { return it }
    val hash = infoHash ?: return null
    val trackers = sources.orEmpty()
        .filter { it.startsWith("tracker:") }
        .joinToString("") { "&tr=" + it.removePrefix("tracker:").encodeUriComponent() }
    val index = (fileIdx ?: 0) + 1
    val dn = behaviorHints?.filename?.let { "&dn=" + it.encodeUriComponent() }.orEmpty()
    return "magnet:?xt=urn:btih:$hash$dn$trackers&index=$index"
}

internal fun StreamInfo.displayTitle(): String =
    listOfNotNull(name, title ?: description)
        .joinToString(" · ") { it.replace('\n', ' ').trim() }
        .ifBlank { "Stream" }

private fun MetaPreview.toSAnime(type: String): SAnime = SAnime.create().apply {
    url = "$type/$id"
    title = name
    thumbnail_url = poster
    background_url = background
    description = this@toSAnime.description
    genre = genres?.joinToString(", ")
    status = SAnime.UNKNOWN
}

@Serializable
private data class CatalogResponse(val metas: List<MetaPreview> = emptyList())

@Serializable
internal data class MetaResponse(val meta: MetaPreview)

@Serializable
internal data class MetaPreview(
    val id: String,
    val type: String? = null,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val videos: List<VideoInfo>? = null,
)

@Serializable
internal data class VideoInfo(
    val id: String,
    // Cinemeta sends `name`; older add-ons use `title` (stremio-core aliases the two).
    val name: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val description: String? = null,
) {
    val displayName: String? get() = (name ?: title)?.takeIf { it.isNotBlank() }
}

@Serializable
internal data class StreamResponse(val streams: List<StreamInfo> = emptyList())

@Serializable
internal data class StreamInfo(
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String>? = null,
    val title: String? = null,
    val description: String? = null,
    val name: String? = null,
    val behaviorHints: StreamHints? = null,
)

@Serializable
internal data class StreamHints(
    val filename: String? = null,
    val proxyHeaders: ProxyHeaders? = null,
)

@Serializable
internal data class ProxyHeaders(val request: Map<String, String>? = null)

@Serializable
private data class SubtitlesResponse(val subtitles: List<SubtitleInfo> = emptyList())

@Serializable
private data class SubtitleInfo(val url: String, val lang: String)
