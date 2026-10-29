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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.Closeable
import java.nio.charset.Charset

/**
 * QuickJS-backed runtime hosting one lnreader JavaScript novel plugin.
 *
 * Loads `assets/lnreader/host.js` (Promise job queue, fetch, require() table, storage) and
 * `assets/lnreader/libs.js` (cheerio, htmlparser2, dayjs, URL), then the plugin itself. Every
 * native capability the plugin gets goes through the synchronous [NativeBridge]: HTTP through
 * the app's OkHttp client, and a key-value store namespaced by plugin id.
 *
 * Reuses [app.cash.quickjs.QuickJs], the engine Aniyomi already bundles.
 *
 * Not thread-safe: a QuickJS instance must be used from a single thread. [close] it when done.
 */
class JsNovelRuntime(pluginId: String, code: String) : Closeable {

    private val network: NetworkHelper by injectLazy()
    private val app: Application by injectLazy()

    private val storage by lazy { app.getSharedPreferences("lnreader_storage", Context.MODE_PRIVATE) }

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
        fun storageKeys(prefix: String): String
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

    /** Calls `plugin[method](...args)`, waits for its promise and returns the resolved value. */
    fun call(method: String, vararg args: JsonElement): JsonElement {
        val script = "__call(${quote(method)}, ${quote(JsonArray(args.toList()).toString())})"
        val out = JSON.parseToJsonElement(quickJs.evaluate(script, "<call>") as String).jsonObject
        if (out["ok"]?.jsonPrimitive?.content != "true") {
            throw Exception(out["error"]?.jsonPrimitive?.content ?: "$method failed")
        }
        return out["value"] ?: JsonNull
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
            this@JsNovelRuntime.logcat(LogPriority.WARN) { "[JsNovel] fetch failed: ${e.message}" }
            buildJsonObject { put("error", e.message ?: e.toString()) }.toString()
        }

        override fun storageGet(key: String): String? = storage.getString(key, null)

        override fun storageSet(key: String, value: String) = storage.edit().putString(key, value).apply()

        override fun storageDelete(key: String) = storage.edit().remove(key).apply()

        override fun storageKeys(prefix: String): String =
            JsonArray(
                storage.all.keys.filter { it.startsWith(prefix) }.map { JsonPrimitive(it.removePrefix(prefix)) },
            ).toString()
    }

    /** Request/response shapes are the JSON exchanged with `doFetch` in host.js. */
    private fun doFetch(requestJson: String): String {
        val req = JSON.parseToJsonElement(requestJson).jsonObject
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
                form.forEach { addFormDataPart(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.content) }
            }.build()
            else -> (req["body"]?.jsonPrimitive?.content ?: "").toRequestBody(headers["content-type"]?.toMediaTypeOrNull())
        }

        val request = Request.Builder()
            .url(req["url"]!!.jsonPrimitive.content)
            .headers(headers)
            .method(method, body)
            .build()

        return network.client.newCall(request).execute().use { res ->
            val contentType = res.body.contentType()
            val bytes = res.body.bytes()
            buildJsonObject {
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
    }

    private companion object {
        val JSON = Json

        val TEXTUAL = Regex("text|json|xml|javascript|html", RegexOption.IGNORE_CASE)

        val HOST_JS: String by lazy { asset("lnreader/host.js") }
        val LIBS_JS: String by lazy { asset("lnreader/libs.js") }

        fun asset(path: String) = Injekt.get<Application>().assets.open(path).bufferedReader().use { it.readText() }

        /** A JSON string literal is a valid JS string literal (ES2019+). */
        fun quote(s: String) = JsonPrimitive(s).toString()
    }
}
