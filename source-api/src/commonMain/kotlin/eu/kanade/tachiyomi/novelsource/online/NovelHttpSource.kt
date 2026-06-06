package eu.kanade.tachiyomi.novelsource.online

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.novelsource.NovelSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest

/**
 * Base class for HTTP-based novel sources. Mirrors
 * [eu.kanade.tachiyomi.animesource.online.AnimeHttpSource] but exposes only the
 * suspend API (no legacy RxJava surface), since novel sources are new.
 */
abstract class NovelHttpSource : NovelSource {

    protected val network: NetworkHelper by injectLazy()

    /** Base url of the site without the trailing slash, e.g. `https://mysite.com`. */
    abstract val baseUrl: String

    open val versionId = 1

    override val id by lazy { generateId(name, lang, versionId) }

    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = network.client

    protected open fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    @Suppress("MemberVisibilityCanBePrivate")
    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    /** Runs the request and returns a successful [Response] (throws otherwise). */
    protected suspend fun Request.await(): Response = client.newCall(this).awaitSuccess()

    override fun toString() = "$name (${lang.uppercase()})"
}
