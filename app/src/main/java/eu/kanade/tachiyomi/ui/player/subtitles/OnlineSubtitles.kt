package eu.kanade.tachiyomi.ui.player.subtitles

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
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
    ) {
        /** Same video and position in the show: a reopened sheet can keep its results. */
        fun sameVideoAs(other: Query): Boolean =
            localVideo == other.localVideo &&
                imdbId == other.imdbId &&
                season == other.season &&
                episode == other.episode &&
                movie == other.movie
    }

    enum class Provider(val label: String, val headers: Headers, val gzipped: Boolean) {
        OPENSUBTITLES("OpenSubtitles", headersOf("User-Agent", LEGACY_USER_AGENT), gzipped = true),
        STREMIO("Stremio", headersOf(), gzipped = false),
    }

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
        val provider: Provider,
    )

    /** Cinemeta answers that came back, including "no match" (null); failed lookups are not kept. */
    private val imdbCache = mutableMapOf<String, String?>()

    suspend fun search(context: Context, query: Query): List<Result> = withIOContext {
        val found = LinkedHashMap<String, Result>()
        var failure: Throwable? = null
        val lang = "sublanguageid-${query.language}"
        val encodedTitle = query.title.trim().lowercase().encodeUriComponent()

        suspend fun <T> attempt(what: String, block: suspend () -> T): T? =
            catching { block() }
                .onFailure {
                    failure = it
                    logcat(LogPriority.WARN, it) { "$what failed" }
                }
                .getOrNull()

        suspend fun legacy(path: String, hash: Boolean = false) {
            attempt("OpenSubtitles search $path") { legacySearch(path, hash, query.episode) }
                ?.forEach { found.putIfAbsent(it.id, it) }
        }

        query.localVideo?.let { video ->
            fileHash(context, video)?.let { (size, hash) ->
                legacy("moviebytesize-$size/moviehash-$hash/$lang", hash = true)
            }
        }

        // A series without an episode number would only find episode 1, wrongly marked as a match.
        if (query.movie || query.episode != null) {
            val imdb = query.imdbId ?: attempt("Cinemeta lookup for ${query.title}") {
                imdbIdFor(query.title, query.movie)
            }
            if (imdb != null) {
                val seasons = if (query.movie) listOf(0) else listOfNotNull(query.season?.takeIf { it > 1 }, 1)
                for (season in seasons.distinct()) {
                    val path = if (query.movie) "movie/$imdb.json" else "series/$imdb:$season:${query.episode}.json"
                    attempt("Stremio subtitles $path") { stremioSearch(path, query.language) }
                        ?.forEach { found.putIfAbsent(it.id, it) }
                    if (found.values.any { it.provider == Provider.STREMIO }) break
                }
            }
        }

        if (query.episode != null) {
            legacy("episode-${query.episode}/query-$encodedTitle/season-${query.season ?: 1}/$lang")
        }
        legacy("query-$encodedTitle/$lang")

        if (found.isEmpty()) failure?.let { throw it }
        rank(found.values, query.episode)
    }

    /** Downloads [result] into [cacheDir] and returns the file mpv should load. */
    suspend fun download(result: Result, cacheDir: File): File = withIOContext {
        val dir = File(cacheDir, "online_subtitles").apply { mkdirs() }
        val target = File(dir, cacheFileName(result))
        if (target.length() > 0) return@withIOContext target

        // Written next to the target and renamed only when complete, so a broken download
        // is never mistaken for a cached subtitle.
        val partial = File(dir, "${target.name}.part")
        try {
            val response = network.client.newCall(GET(result.link, result.provider.headers)).awaitSuccess()
            response.body.byteStream().use { body ->
                val input = if (result.provider.gzipped) GZIPInputStream(body) else body
                partial.outputStream().use { input.copyTo(it) }
            }
            check(partial.length() > 0) { "Empty subtitle file" }
            check(partial.renameTo(target)) { "Could not save the subtitle" }
        } finally {
            partial.delete()
        }
        target
    }

    private suspend fun legacySearch(path: String, hash: Boolean, episode: Int?): List<Result> {
        val response = network.client
            .newCall(GET("$LEGACY_URL/$path", Provider.OPENSUBTITLES.headers))
            .awaitSuccess()
        return json.parseToJsonElement(response.body.string()).jsonArray
            .mapNotNull { (it as? JsonObject)?.let { row -> parseLegacyRow(row, hash, episode) } }
    }

    private suspend fun stremioSearch(path: String, language: String): List<Result> {
        val response = network.client.newCall(GET("$STREMIO_URL/subtitles/$path")).awaitSuccess()
        return json.parseToJsonElement(response.body.string()).jsonObject["subtitles"]?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.let { row -> parseStremioRow(row, language) } }
    }

    /** Best Cinemeta match for [title]; only exact or prefix name matches are trusted. Throws on network errors. */
    private suspend fun imdbIdFor(title: String, movie: Boolean): String? {
        val wanted = normalize(title)
        if (wanted.isEmpty()) return null
        val key = "${if (movie) "m" else "s"}:$wanted"
        if (imdbCache.containsKey(key)) return imdbCache[key]
        val kind = if (movie) "movie" else "series"
        val response = network.client
            .newCall(GET("$CINEMETA_URL/catalog/$kind/top/search=${title.encodeUriComponent()}.json"))
            .awaitSuccess()
        val found = json.parseToJsonElement(response.body.string()).jsonObject["metas"]?.jsonArray.orEmpty()
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
        imdbCache[key] = found
        return found
    }

    /** Opens a local video (path, file:// or content:// uri) and hashes it; streams return null. */
    private fun fileHash(context: Context, video: String): Pair<Long, String>? = catching {
        val channel: FileChannel? = when {
            // Closing the channel closes the stream, which closes the descriptor.
            video.startsWith("content://") ->
                context.contentResolver.openFileDescriptor(Uri.parse(video), "r")
                    ?.let { ParcelFileDescriptor.AutoCloseInputStream(it).channel }
            video.startsWith("file://") -> Uri.parse(video).path?.let { RandomAccessFile(it, "r").channel }
            video.startsWith("/") -> RandomAccessFile(video, "r").channel
            else -> null
        }
        channel?.use { openSubtitlesHash(it) }
    }.onFailure { logcat(LogPriority.WARN, it) { "Subtitle hash failed" } }.getOrNull()

    companion object {
        private const val LEGACY_URL = "https://rest.opensubtitles.org/search"
        private const val LEGACY_USER_AGENT = "TemporaryUserAgent"
        private const val STREMIO_URL = "https://opensubtitles-v3.strem.io"
        private const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
        private const val HASH_CHUNK = 65536L
        private val SUBTITLE_FORMATS = setOf("srt", "ass", "ssa", "vtt", "sub")

        /**
         * Languages offered in the sheet, as OpenSubtitles codes: ISO 639-2/B plus
         * OpenSubtitles' own "pob" (Brazilian Portuguese).
         */
        val LANGUAGES = listOf(
            "ita", "eng", "spa", "por", "pob", "fre", "ger", "rus", "ara", "ind", "jpn",
            "kor", "chi", "tur", "vie", "tha", "pol", "dut", "hin",
        )

        // Locale.isO3Language gives terminology codes. These are the common languages whose
        // bibliographic code (used by OpenSubtitles) differs; other codes pass through unchanged.
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

        /**
         * First preferred subtitle language (e.g. "it,en"), else the device language, as an
         * OpenSubtitles code; "eng" when the language has no three-letter code.
         */
        fun defaultLanguage(preferred: String): String {
            val tag = preferred.split(",").map(String::trim).firstOrNull(String::isNotEmpty)
            val locale = tag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
            val code = runCatching { locale.isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return "eng"
            return TERMINOLOGY_TO_BIBLIOGRAPHIC[code] ?: code
        }

        /**
         * Season written in an anime title ("Season 2", "S2", "2nd Season"). Only 2..39 count:
         * season 1 is the default anyway, and larger numbers are usually something else.
         */
        fun seasonFromTitle(title: String): Int? {
            val match = Regex("""(?:season|\bs)\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)
                ?: Regex("""(\d+)(?:st|nd|rd|th)\s+season""", RegexOption.IGNORE_CASE).find(title)
            return match?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 2..39 }
        }

        /**
         * OpenSubtitles hash: file size plus the 64-bit little-endian words of the first and
         * last 64 KiB, wrapping on overflow. Returns the size and the 16-digit hex hash, or
         * null for files under 128 KiB.
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

        /**
         * Hash matches first, then episode matches, then the most downloaded. When the episode
         * is known and something matches, only the matches are kept.
         */
        internal fun rank(results: Collection<Result>, episode: Int?): List<Result> {
            val sorted = results.sortedWith(
                compareByDescending<Result> { (if (it.hashMatch) 4 else 0) + (if (it.episodeMatch) 2 else 0) }
                    .thenByDescending { it.downloads },
            )
            val matching = sorted.filter { it.hashMatch || it.episodeMatch }
            return if (episode != null && matching.isNotEmpty()) matching else sorted
        }

        /** One row of the legacy OpenSubtitles REST search; null when it cannot be downloaded. */
        internal fun parseLegacyRow(row: JsonObject, hash: Boolean, episode: Int?): Result? {
            val id = row.string("IDSubtitleFile") ?: return null
            val name = row.string("SubFileName") ?: return null
            val link = row.string("SubDownloadLink") ?: return null
            return Result(
                id = id,
                name = name,
                language = row.string("LanguageName") ?: row.string("SubLanguageID").orEmpty(),
                format = subtitleFormat(row.string("SubFormat")),
                link = link,
                downloads = row.string("SubDownloadsCnt")?.toIntOrNull() ?: 0,
                hearingImpaired = row.string("SubHearingImpaired") == "1",
                hashMatch = hash,
                episodeMatch = episode != null &&
                    (row.string("SeriesEpisode") == "$episode" || mentionsEpisode(name, episode)),
                provider = Provider.OPENSUBTITLES,
            )
        }

        /**
         * One row of the Stremio add-on, which is already scoped to the requested episode;
         * rows in other languages are dropped.
         */
        internal fun parseStremioRow(row: JsonObject, language: String): Result? {
            if (row.string("lang") != language) return null
            val url = row.string("url") ?: return null
            val name = row.string("subtitleFileName") ?: row.string("movieReleaseName") ?: row.string("id") ?: url
            return Result(
                id = "stremio-${row.string("id") ?: url.hashCode()}",
                name = name,
                language = language,
                format = subtitleFormat(name.substringAfterLast('.', "")),
                link = url,
                downloads = 0,
                hearingImpaired = false,
                hashMatch = false,
                episodeMatch = true,
                provider = Provider.STREMIO,
            )
        }

        /** Cache file name from the id and a known extension only, so server data cannot pick the path. */
        internal fun cacheFileName(result: Result): String {
            val ext = if (result.format == "ssa") "ass" else result.format
            return "${result.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.$ext"
        }

        /** A known subtitle extension in lower case, else "srt". */
        private fun subtitleFormat(value: String?): String =
            value?.lowercase()?.takeIf { it in SUBTITLE_FORMATS } ?: "srt"

        /**
         * True when [name] carries [episode] after an episode marker ("E03", "Ep 3", "Episode 3",
         * " - 03"), so season numbers like the "02" in "S02E07" do not count.
         */
        internal fun mentionsEpisode(name: String, episode: Int): Boolean =
            Regex("""(?:e|ep\.?|\s-)\s*0*$episode(?![0-9])""", RegexOption.IGNORE_CASE).containsMatchIn(name)

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }

        private fun String.encodeUriComponent(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

        private fun normalize(value: String): String =
            value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}

/** Like [runCatching], but lets coroutine cancellation through instead of treating it as a failure. */
internal inline fun <T> catching(block: () -> T): kotlin.Result<T> =
    try {
        kotlin.Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        kotlin.Result.failure(e)
    }
