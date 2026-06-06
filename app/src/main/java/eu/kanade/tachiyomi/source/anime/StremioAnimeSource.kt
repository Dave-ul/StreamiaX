package eu.kanade.tachiyomi.source.anime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response

/**
 * Built-in source that bridges the Stremio add-on HTTP protocol into Aniyomi.
 *
 * Catalog and metadata come from Cinemeta; streams come from Torrentio. Only streams
 * that expose a direct playable URL are surfaced (torrent-only results that require
 * Stremio's local streaming server are skipped, since the player cannot resolve them).
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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

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
        val skip = (page - 1) * PAGE_SIZE
        val extra = buildList {
            if (!query.isNullOrBlank()) add("search=$query")
            if (skip > 0) add("skip=$skip")
        }.joinToString("&")
        val suffix = if (extra.isEmpty()) "" else "/$extra"
        val url = "$CATALOG_URL/catalog/$type/$catalogId$suffix.json"
        val metas = get<CatalogResponse>(url).metas
        return AnimesPage(
            animes = metas.map { it.toSAnime(type) },
            hasNextPage = query.isNullOrBlank() && metas.size >= PAGE_SIZE,
        )
    }

    // ---- Details ----

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (type, realId) = anime.url.parseEntryUrl()
        val meta = get<MetaResponse>("$CATALOG_URL/meta/$type/$realId.json").meta
        return meta.toSAnime(type).apply { initialized = true }
    }

    // ---- Episodes ----

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (type, realId) = anime.url.parseEntryUrl()
        if (type == "movie") {
            return listOf(
                SEpisode.create().apply {
                    url = "movie/$realId"
                    name = "Movie"
                    episode_number = 1f
                },
            )
        }
        val meta = get<MetaResponse>("$CATALOG_URL/meta/$type/$realId.json").meta
        return meta.videos.orEmpty().mapIndexed { idx, v ->
            SEpisode.create().apply {
                url = "$type/${v.id}"
                name = v.title ?: "Episode ${v.episode ?: idx + 1}"
                episode_number = (v.episode ?: idx + 1).toFloat()
            }
        }.reversed()
    }

    // ---- Videos / Hosters ----

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = fetchStreams(episode.url).toHosterList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> = fetchStreams(episode.url)

    private suspend fun fetchStreams(episodeUrl: String): List<Video> {
        val (type, realId) = episodeUrl.parseEntryUrl()
        val streams = get<StreamResponse>("$STREAM_URL/stream/$type/$realId.json").streams
        return streams.mapNotNull { s ->
            val direct = s.url ?: return@mapNotNull null
            Video(videoUrl = direct, videoTitle = s.title ?: s.name ?: "Stream")
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

    private fun MetaPreview.toSAnime(type: String): SAnime = SAnime.create().apply {
        url = "$type/$id"
        title = name
        thumbnail_url = poster
        background_url = background
        description = this@toSAnime.description
        genre = genres?.joinToString(", ")
        status = SAnime.UNKNOWN
    }

    // Abstract request/parse members are unused: the suspend API above is overridden directly.
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
    override fun videoUrlParse(response: Response): String = unsupported()

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("StremioAnimeSource uses the suspend API")

    companion object {
        const val ID = 6_900_000_000_000_000_001L
        private const val CATALOG_URL = "https://v3-cinemeta.strem.io"
        private const val STREAM_URL = "https://torrentio.strem.fun"
        private const val PAGE_SIZE = 100
    }
}

@Serializable
private data class CatalogResponse(val metas: List<MetaPreview> = emptyList())

@Serializable
private data class MetaResponse(val meta: MetaPreview)

@Serializable
private data class MetaPreview(
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
private data class VideoInfo(
    val id: String,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

@Serializable
private data class StreamResponse(val streams: List<StreamInfo> = emptyList())

@Serializable
private data class StreamInfo(
    val url: String? = null,
    val title: String? = null,
    val name: String? = null,
)
