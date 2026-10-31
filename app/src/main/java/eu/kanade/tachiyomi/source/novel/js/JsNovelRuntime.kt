package eu.kanade.tachiyomi.source.novel.js

import android.app.Application
import android.content.Context
import android.util.Base64
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * QuickJS-backed runtime hosting one lnreader JavaScript novel plugin.
 *
 * Loads `assets/lnreader/host.js` (Promise job queue, fetch, require() table, storage) and
 * `assets/lnreader/libs.js` (cheerio, htmlparser2, dayjs, URL), then the plugin itself. Every
 * native capability the plugin gets goes through the synchronous [NativeBridge]: HTTP through
 * the app's OkHttp client (public addresses only, checked before connecting; size-capped
 * responses), and a key-value store whose keys are prefixed with the plugin id on this side. The
 * prefix (`<id>_DB_`) is kept as it is for the data already stored; it keeps plugins apart as
 * long as no plugin id contains `_DB_`.
 *
 * The app's cookie jar is shared on purpose: the Cloudflare interceptor stores its clearance there.
 *
 * Reuses [app.cash.quickjs.QuickJs], the engine Aniyomi already bundles.
 *
 * Not thread-safe: a QuickJS instance must be used from a single thread, which [JsPluginThread]
 * provides. Only [cancel] may be called from another thread. [close] it when done.
 *
 * A call fails with [JsTimeoutException] past its deadline or once cancelled, and with
 * [JsNetworkException] when the plugin failed after a network failure; [lastNetworkError] tells
 * the caller about one the plugin hid.
 */
class JsNovelRuntime(pluginId: String, code: String) : JsPluginThread.Runtime {

    private val app: Application by injectLazy()

    private val storage by lazy { app.getSharedPreferences("lnreader_storage", Context.MODE_PRIVATE) }
    private val storagePrefix = "${pluginId}_DB_"

    // A fetch ends the call it belongs to when the call is past its deadline or cancelled
    @Volatile
    private var deadline = 0L

    @Volatile
    private var cancelled = false

    // Set when a fetch was refused for the deadline or a cancel: the call cannot have its real answer
    @Volatile
    private var refused = false

    /**
     * The network failure seen during the last call, if any: an I/O error, or an HTTP 403, 429 or
     * 5xx answer, which plugins often turn into an empty result. Read it on the plugin thread.
     */
    @Volatile
    var lastNetworkError: IOException? = null
        private set

    @Volatile
    private var inFlight: Call? = null

    private val quickJs = QuickJs.create()

    /** Native bridge exposed to JS as `__native`. QuickJs forbids overloads, so names are unique. */
    interface NativeBridge {
        fun log(message: String)
        fun sleep(ms: Double)
        fun atob(data: String): String
        fun btoa(data: String): String
        fun b64ToUtf8(data: String): String
        fun utf8ToB64(data: String): String
        fun fetch(requestJson: String): String
        fun storageGet(key: String): String?
        fun storageSet(key: String, value: String)
        fun storageDelete(key: String)
        fun storageKeys(): String
    }

    init {
        try {
            quickJs.set("__native", NativeBridge::class.java, Bridge())
            quickJs.evaluate(HOST_JS, "host.js")
            quickJs.evaluate(LIBS_JS, "libs.js")
            quickJs.evaluate("__load(${quote(pluginId)}, ${quote(code)})", "$pluginId.js")
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override fun begin() {
        cancelled = false
        refused = false
        lastNetworkError = null
    }

    /**
     * Calls `plugin[method](...args)`, waits for its promise and returns the resolved value.
     * Fails with [JsTimeoutException] once [CALL_TIMEOUT_MS] pass or the call is cancelled: timers
     * stop running and fetches fail from then on.
     */
    fun call(method: String, vararg args: JsonElement): JsonElement {
        deadline = System.currentTimeMillis() + CALL_TIMEOUT_MS
        val script = "__call(${quote(method)}, ${quote(JsonArray(args.toList()).toString())}, $deadline)"
        val out = Json.parseToJsonElement(quickJs.evaluate(script, "<call>") as String).jsonObject
        if (out["ok"]?.jsonPrimitive?.content != "true") {
            val error = out["error"]?.jsonPrimitive?.content ?: "$method failed"
            // Whatever the plugin made of its failed fetches, the cause is the deadline
            if (refused || System.currentTimeMillis() >= deadline) {
                throw JsTimeoutException("$method timed out: $error")
            }
            lastNetworkError?.let { throw JsNetworkException("$method: $error", it) }
            throw Exception(error)
        }
        // A plugin that caught the refused fetch returns an empty result, which is not its answer
        if (refused) throw JsTimeoutException("$method timed out")
        return out["value"] ?: JsonNull
    }

    /** Makes the call in progress fail fast: its HTTP request is cancelled, and so are its next ones. */
    override fun cancel() {
        cancelled = true
        inFlight?.cancel()
    }

    override fun close() {
        runCatching { quickJs.close() }
    }

    private inner class Bridge : NativeBridge {

        override fun log(message: String) = this@JsNovelRuntime.logcat { "[JsNovel] $message" }

        override fun sleep(ms: Double) = Thread.sleep(ms.toLong().coerceIn(0, 5000))

        override fun atob(data: String) = String(Base64.decode(data, Base64.DEFAULT), Charsets.ISO_8859_1)

        override fun btoa(data: String): String =
            Base64.encodeToString(data.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP)

        override fun b64ToUtf8(data: String) = String(Base64.decode(data, Base64.DEFAULT), Charsets.UTF_8)

        override fun utf8ToB64(data: String): String = Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)

        override fun fetch(requestJson: String): String = try {
            doFetch(requestJson)
        } catch (e: Exception) {
            if (e is IOException) lastNetworkError = e
            this@JsNovelRuntime.logcat(LogPriority.WARN) { "[JsNovel] fetch failed: ${e.message}" }
            buildJsonObject { put("error", e.message ?: e.toString()) }.toString()
        }

        override fun storageGet(key: String): String? = storage.getString(storagePrefix + key, null)

        override fun storageSet(key: String, value: String) =
            storage.edit().putString(storagePrefix + key, value).apply()

        override fun storageDelete(key: String) = storage.edit().remove(storagePrefix + key).apply()

        override fun storageKeys(): String =
            JsonArray(
                storage.all.keys
                    .filter { it.startsWith(storagePrefix) }
                    .map { JsonPrimitive(it.removePrefix(storagePrefix)) },
            ).toString()
    }

    /** Request/response shapes are the JSON exchanged with `doFetch` in host.js. */
    private fun doFetch(requestJson: String): String {
        val remaining = deadline - System.currentTimeMillis()
        if (cancelled || remaining <= 0) {
            refused = true
            throw IOException("the plugin call was cancelled or timed out")
        }
        val req = Json.parseToJsonElement(requestJson).jsonObject
        val method = req["method"]!!.jsonPrimitive.content
        val form = req["form"]?.jsonArray
        val headers = Headers.Builder().apply {
            req["headers"]?.jsonObject?.forEach { (name, value) ->
                // A multipart body brings its own content-type with the boundary.
                if (form == null || name != "content-type") addUnsafeNonAscii(name, value.jsonPrimitive.content)
            }
        }.build()

        val body: RequestBody? = when {
            method == "GET" || method == "HEAD" -> null
            form != null -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                form.forEach {
                    addFormDataPart(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.content)
                }
            }.build()
            else -> (req["body"]?.jsonPrimitive?.content ?: "").toRequestBody(
                headers["content-type"]?.toMediaTypeOrNull(),
            )
        }

        val request = Request.Builder()
            .url(req["url"]!!.jsonPrimitive.content)
            .headers(headers)
            .method(method, body)
            .build()

        val call = client.newCall(request)
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
        inFlight = call
        // A cancel() between the check above and inFlight being set would be missed otherwise
        if (cancelled) call.cancel()
        return try {
            call.execute().use { toJson(it, req) }
        } finally {
            inFlight = null
        }
    }

    private fun toJson(res: Response, req: JsonObject): String {
        if (res.code == 403 || res.code == 429 || res.code >= 500) {
            lastNetworkError = IOException("HTTP ${res.code} for ${res.request.url}")
        }
        val contentType = res.body.contentType()
        val bytes = res.body.readCapped()
        return buildJsonObject {
            put("status", res.code)
            put("statusText", res.message)
            put("url", res.request.url.toString())
            put("redirected", res.priorResponse != null)
            putJsonObject("headers") {
                res.headers.names().forEach { put(it.lowercase(), res.headers(it).joinToString(", ")) }
            }
            if (contentType == null || TEXTUAL.containsMatchIn(contentType.toString())) {
                val charset = req["encoding"]?.jsonPrimitive?.content
                    ?.let { runCatching { Charset.forName(it) }.getOrNull() }
                    ?: contentType?.charset()
                    ?: Charsets.UTF_8
                put("body", String(bytes, charset))
            } else {
                put("body64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }.toString()
    }

    private companion object {
        /** Under [JsNovelSource]'s own timeout, so the plugin gets to fail with its own error first. */
        const val CALL_TIMEOUT_MS = 60_000L

        /** Far above any chapter page, and small enough that a few at once fit in memory. */
        const val MAX_BODY_BYTES = 16L * 1024 * 1024

        /**
         * The app's client (cookies, Cloudflare, DoH, user agent), kept off this device and its
         * network; see [PublicAddressInterceptor]. Requests through a proxy are not checked.
         */
        val client: OkHttpClient by lazy {
            val app = Injekt.get<NetworkHelper>().client
            app.newBuilder()
                .dns(PublicDns(app.dns))
                .addInterceptor(PublicAddressInterceptor())
                .addNetworkInterceptor(PublicAddressInterceptor())
                .build()
        }

        fun ResponseBody.readCapped(): ByteArray {
            val source = source()
            if (source.request(MAX_BODY_BYTES + 1)) {
                throw IOException("response larger than ${MAX_BODY_BYTES / 1024 / 1024} MB")
            }
            return source.buffer.readByteArray()
        }

        val TEXTUAL = Regex("text|json|xml|javascript|html", RegexOption.IGNORE_CASE)

        val HOST_JS: String by lazy { asset("lnreader/host.js") }
        val LIBS_JS: String by lazy { asset("lnreader/libs.js") }

        fun asset(path: String) = Injekt.get<Application>().assets.open(path).bufferedReader().use { it.readText() }

        /** A JSON string literal is a valid JS string literal (ES2019+). */
        fun quote(s: String) = JsonPrimitive(s).toString()
    }
}

/** A plugin call that failed after one of its requests did: a network problem, not the plugin's answer. */
class JsNetworkException(message: String, cause: IOException) : IOException(message, cause)
