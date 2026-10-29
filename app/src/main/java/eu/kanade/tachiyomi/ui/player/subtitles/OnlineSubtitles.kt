package eu.kanade.tachiyomi.ui.player.subtitles

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import logcat.LogPriority
import okhttp3.Headers.Companion.headersOf
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Searches subtitles on OpenSubtitles while a video is playing.
 *
 * Modelled on Dartotsu's online subtitle search (lib/Screen/Player/Engine/OnlineSubtitles.dart):
 * the file hash and the title go to the legacy OpenSubtitles REST API, and the IMDb id
 * (taken from Stremio entries or looked up on Cinemeta) goes to the Stremio OpenSubtitles add-on.
 */
class OnlineSubtitles(
    private val network: NetworkHelper = Injekt.get(),
    private val json: Json = Injekt.get(),
) {

    data class Query(
        val title: String,
        val language: String,
        val season: Int? = null,
        val episode: Int? = null,
        val movie: Boolean = false,
        val imdbId: String? = null,
        /** A local file (path or content:// uri), hashed for exact release matches. */
        val localVideo: String? = null,
    )

    data class Result(
        val id: String,
        val name: String,
        val language: String,
        val format: String,
        val link: String,
        val downloads: Int,
        val hearingImpaired: Boolean,
        val hashMatch: Boolean,
        val episodeMatch: Boolean,
        val gzipped: Boolean,
        val provider: String,
    )

    private val imdbCache = mutableMapOf<String, String?>()

    suspend fun search(context: Context, query: Query): List<Result> = withIOContext {
        val found = LinkedHashMap<String, Result>()
        var failure: Throwable? = null
        val lang = "sublanguageid-${query.language}"
        val encodedTitle = URLEncoder.encode(query.title.trim().lowercase(), "UTF-8").replace("+", "%20")

        suspend fun legacy(path: String, hash: Boolean = false) {
            runCatching { legacySearch(path) }
                .onSuccess { rows ->
                    rows.mapNotNull { legacyResult(it, hash, query.episode) }
                        .forEach { found.putIfAbsent(it.id, it) }
                }
                .onFailure {
                    failure = it
                    logcat(LogPriority.WARN, it) { "OpenSubtitles search $path failed" }
                }
        }

        query.localVideo?.let { video ->
            fileHash(context, video)?.let { (size, hash) ->
                legacy("moviebytesize-$size/moviehash-$hash/$lang", hash = true)
            }
        }

        val imdb = query.imdbId ?: imdbIdFor(query.title, query.movie)
        if (imdb != null) {
            val seasons = if (query.movie) listOf(0) else listOfNotNull(query.season?.takeIf { it > 1 }, 1).distinct()
            for (season in seasons) {
                val path = if (query.movie) "movie/$imdb.json" else "series/$imdb:$season:${query.episode ?: 1}.json"
                runCatching { stremioSearch(path, query.language) }
                    .onSuccess { it.forEach { result -> found.putIfAbsent(result.id, result) } }
                    .onFailure {
                        failure = it
                        logcat(LogPriority.WARN, it) { "Stremio subtitles $path failed" }
                    }
                if (found.values.any { it.provider == PROVIDER_STREMIO }) break
            }
        }

        if (query.episode != null) {
            legacy("episode-${query.episode}/query-$encodedTitle/season-${query.season ?: 1}/$lang")
        }
        legacy("query-$encodedTitle/$lang")

        if (found.isEmpty()) failure?.let { throw it }

        val sorted = found.values.sortedWith(
            compareByDescending<Result> { (if (it.hashMatch) 4 else 0) + (if (it.episodeMatch) 2 else 0) }
                .thenByDescending { it.downloads },
        )
        val matching = sorted.filter { it.hashMatch || it.episodeMatch }
        if (query.episode != null && matching.isNotEmpty()) matching else sorted
    }

    /** Downloads [result] into [cacheDir] and returns the file mpv should load. */
    suspend fun download(result: Result, cacheDir: File): File = withIOContext {
        val dir = File(cacheDir, "online_subtitles").apply { mkdirs() }
        val ext = if (result.format.equals("ssa", ignoreCase = true)) "ass" else result.format.lowercase()
        val target = File(dir, "${result.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.$ext")
        if (target.length() > 0) return@withIOContext target

        val headers = if (result.provider == PROVIDER_OPENSUBTITLES) {
            headersOf("User-Agent", LEGACY_USER_AGENT)
        } else {
            headersOf()
        }
        val response = network.client.newCall(GET(result.link, headers)).awaitSuccess()
        response.body.byteStream().use { body ->
            val input = if (result.gzipped) GZIPInputStream(body) else body
            target.outputStream().use { input.copyTo(it) }
        }
        target
    }

    private suspend fun legacySearch(path: String): JsonArray {
        val response = network.client
            .newCall(GET("$LEGACY_URL/$path", headersOf("User-Agent", LEGACY_USER_AGENT)))
            .awaitSuccess()
        return json.parseToJsonElement(response.body.string()).jsonArray
    }

    private fun legacyResult(row: JsonElement, hash: Boolean, episode: Int?): Result? {
        val obj = row as? JsonObject ?: return null
        val id = obj.string("IDSubtitleFile") ?: return null
        val name = obj.string("SubFileName") ?: return null
        val link = obj.string("SubDownloadLink") ?: return null
        return Result(
            id = id,
            name = name,
            language = obj.string("LanguageName") ?: obj.string("SubLanguageID").orEmpty(),
            format = obj.string("SubFormat") ?: "srt",
            link = link,
            downloads = obj.string("SubDownloadsCnt")?.toIntOrNull() ?: 0,
            hearingImpaired = obj.string("SubHearingImpaired") == "1",
            hashMatch = hash,
            episodeMatch = episode != null &&
                (obj.string("SeriesEpisode") == "$episode" || mentionsEpisode(name, episode)),
            gzipped = true,
            provider = PROVIDER_OPENSUBTITLES,
        )
    }

    private suspend fun stremioSearch(path: String, language: String): List<Result> {
        val response = network.client.newCall(GET("$STREMIO_URL/subtitles/$path")).awaitSuccess()
        val rows = json.parseToJsonElement(response.body.string()).jsonObject["subtitles"]?.jsonArray.orEmpty()
        return rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            if (obj.string("lang") != language) return@mapNotNull null
            val url = obj.string("url") ?: return@mapNotNull null
            val name = obj.string("subtitleFileName") ?: obj.string("movieReleaseName") ?: obj.string("id") ?: url
            Result(
                id = "stremio-${obj.string("id") ?: url.hashCode()}",
                name = name,
                language = language,
                format = name.substringAfterLast('.', "").lowercase().takeIf { it in SUBTITLE_FORMATS } ?: "srt",
                link = url,
                downloads = 0,
                hearingImpaired = false,
                hashMatch = false,
                episodeMatch = true,
                gzipped = false,
                provider = PROVIDER_STREMIO,
            )
        }
    }

    /** Best Cinemeta match for [title]; only exact or prefix name matches are trusted. */
    private suspend fun imdbIdFor(title: String, movie: Boolean): String? {
        val wanted = normalize(title)
        if (wanted.isEmpty()) return null
        val key = "${if (movie) "m" else "s"}:$wanted"
        if (imdbCache.containsKey(key)) return imdbCache[key]
        val found = runCatching {
            val kind = if (movie) "movie" else "series"
            val encoded = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
            val response = network.client
                .newCall(GET("$CINEMETA_URL/catalog/$kind/top/search=$encoded.json"))
                .awaitSuccess()
            json.parseToJsonElement(response.body.string()).jsonObject["metas"]?.jsonArray.orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { meta ->
                    val imdb = meta.string("imdb_id") ?: return@mapNotNull null
                    val name = normalize(meta.string("name").orEmpty())
                    val score = when {
                        name == wanted -> 100
                        name.startsWith(wanted) -> 60
                        else -> 0
                    }
                    imdb to score
                }
                .filter { it.second >= 60 }
                .maxByOrNull { it.second }
                ?.first
        }.onFailure { logcat(LogPriority.WARN, it) { "Cinemeta lookup failed for $title" } }.getOrNull()
        imdbCache[key] = found
        return found
    }

    /** Opens a local video (path, file:// or content:// uri) and hashes it; streams return null. */
    private fun fileHash(context: Context, video: String): Pair<Long, String>? = runCatching {
        val channel: FileChannel? = when {
            video.startsWith("content://") ->
                context.contentResolver.openFileDescriptor(Uri.parse(video), "r")
                    ?.let { FileInputStream(it.fileDescriptor).channel }
            video.startsWith("file://") -> Uri.parse(video).path?.let { RandomAccessFile(it, "r").channel }
            video.startsWith("/") -> RandomAccessFile(video, "r").channel
            else -> null
        }
        channel?.use { openSubtitlesHash(it) }
    }.onFailure { logcat(LogPriority.WARN, it) { "Subtitle hash failed" } }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }

    companion object {
        private const val LEGACY_URL = "https://rest.opensubtitles.org/search"
        private const val LEGACY_USER_AGENT = "TemporaryUserAgent"
        private const val STREMIO_URL = "https://opensubtitles-v3.strem.io"
        private const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
        private const val HASH_CHUNK = 65536L
        private val SUBTITLE_FORMATS = setOf("srt", "ass", "ssa", "vtt", "sub")

        const val PROVIDER_OPENSUBTITLES = "OpenSubtitles"
        const val PROVIDER_STREMIO = "Stremio"

        /** Languages offered in the sheet, as OpenSubtitles (ISO 639-2/B) codes. */
        val LANGUAGES = listOf(
            "ita", "eng", "spa", "por", "pob", "fre", "ger", "rus", "ara", "ind", "jpn",
            "kor", "chi", "tur", "vie", "tha", "pol", "dut", "hin",
        )

        // Locale.isO3Language gives terminology codes; OpenSubtitles wants the bibliographic ones.
        private val TERMINOLOGY_TO_BIBLIOGRAPHIC = mapOf(
            "fra" to "fre", "deu" to "ger", "zho" to "chi", "nld" to "dut", "ces" to "cze",
            "ron" to "rum", "ell" to "gre", "fas" to "per", "slk" to "slo", "sqi" to "alb",
        )

        fun languageName(code: String): String {
            val tag = when (code) {
                "pob" -> "pt-BR"
                else -> TERMINOLOGY_TO_BIBLIOGRAPHIC.entries.firstOrNull { it.value == code }?.key ?: code
            }
            return Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
                .ifEmpty { code }
        }

        /** First preferred subtitle language (e.g. "it,en"), else the device language, as a 639-2/B code. */
        fun defaultLanguage(preferred: String): String {
            val tag = preferred.split(",").map(String::trim).firstOrNull(String::isNotEmpty)
            val locale = tag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
            val code = runCatching { locale.isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return "eng"
            return TERMINOLOGY_TO_BIBLIOGRAPHIC[code] ?: code
        }

        /** Season number written in an anime title ("Season 2", "S2", "2nd Season"). */
        fun seasonFromTitle(title: String): Int? {
            val match = Regex("""(?:season|\bs)\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)
                ?: Regex("""(\d+)(?:st|nd|rd|th)\s+season""", RegexOption.IGNORE_CASE).find(title)
            return match?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 2..39 }
        }

        /**
         * OpenSubtitles hash: file size plus the 64-bit little-endian words of the first and
         * last 64 KiB, wrapping on overflow. Returns the size and the 16-digit hex hash.
         */
        internal fun openSubtitlesHash(channel: FileChannel): Pair<Long, String>? {
            val size = channel.size()
            if (size < HASH_CHUNK * 2) return null
            var hash = size
            for (position in longArrayOf(0, size - HASH_CHUNK)) {
                val buffer = ByteBuffer.allocate(HASH_CHUNK.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                while (buffer.hasRemaining() && channel.read(buffer, position + buffer.position()) > 0) {
                    // keep reading until the chunk is full
                }
                buffer.flip()
                while (buffer.remaining() >= Long.SIZE_BYTES) hash += buffer.long
            }
            return size to java.lang.Long.toHexString(hash).padStart(16, '0')
        }

        internal fun mentionsEpisode(name: String, episode: Int): Boolean =
            Regex("""(?<![0-9])0*$episode(?![0-9])""").containsMatchIn(name)

        private fun normalize(value: String): String =
            value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
