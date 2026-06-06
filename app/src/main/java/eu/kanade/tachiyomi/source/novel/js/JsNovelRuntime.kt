package eu.kanade.tachiyomi.source.novel.js

import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import logcat.LogPriority
import okhttp3.Headers
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.Closeable

/**
 * QuickJS-backed runtime that hosts lnreader-style JavaScript novel plugins.
 *
 * This is the engine foundation: it owns the QuickJS lifecycle, wires a `console` for
 * logging, and exposes a synchronous native HTTP bridge (OkHttp) that the JS layer builds
 * `fetch`/`fetchApi`/`fetchText` on top of.
 *
 * Reuses [app.cash.quickjs.QuickJs], the engine Aniyomi already bundles, which binds native
 * implementations through plain interfaces ([set]) rather than per-call argument arrays.
 *
 * The full lnreader plugin host (cheerio shim, `@libs` modules, async fetch, and ES-module
 * plugin loading) is layered on top of this in later steps.
 *
 * Not thread-safe: a QuickJS instance must be used from a single thread. Create one runtime
 * per worker and [close] it when done.
 */
class JsNovelRuntime : Closeable {

    private val network: NetworkHelper by injectLazy()

    private val quickJs = QuickJs.create()

    /** Native bridge exposed to JS as `__http`. */
    interface HttpBridge {
        fun get(url: String): String
    }

    /** Native bridge exposed to JS as `__console`. */
    interface ConsoleBridge {
        fun log(message: String)
    }

    init {
        quickJs.set(
            "__http",
            HttpBridge::class.java,
            object : HttpBridge {
                override fun get(url: String): String = runCatching {
                    network.client.newCall(GET(url, DEFAULT_HEADERS)).execute().use { it.body.string() }
                }.getOrElse { e ->
                    logcat(LogPriority.WARN) { "__http.get failed for $url: ${e.message}" }
                    ""
                }
            },
        )
        quickJs.set(
            "__console",
            ConsoleBridge::class.java,
            object : ConsoleBridge {
                override fun log(message: String) = this@JsNovelRuntime.logcat { "[JsNovel] $message" }
            },
        )
        quickJs.evaluate(BOOTSTRAP, "<bootstrap>")
    }

    /** Evaluates [script] and returns its result coerced to a string. */
    fun evalString(script: String, fileName: String = "<eval>"): String =
        quickJs.evaluate(script, fileName)?.toString().orEmpty()

    /** Evaluates [script] for its side effects. */
    fun eval(script: String, fileName: String = "<eval>") {
        quickJs.evaluate(script, fileName)
    }

    override fun close() {
        runCatching { quickJs.close() }
    }

    private companion object {
        val DEFAULT_HEADERS: Headers = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Android) StreamiaX")
            .build()

        const val BOOTSTRAP = """
            var console = {
                log: function() { __console.log(Array.prototype.join.call(arguments, ' ')); },
                warn: function() { __console.log(Array.prototype.join.call(arguments, ' ')); },
                error: function() { __console.log(Array.prototype.join.call(arguments, ' ')); },
                info: function() { __console.log(Array.prototype.join.call(arguments, ' ')); }
            };
            function fetchText(url) { return __http.get(url); }
            function fetchApi(url) {
                var body = __http.get(url);
                return {
                    text: function() { return body; },
                    json: function() { return JSON.parse(body); }
                };
            }
        """
    }
}
