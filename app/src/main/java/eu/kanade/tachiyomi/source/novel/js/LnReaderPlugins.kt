package eu.kanade.tachiyomi.source.novel.js

import android.app.Application
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import java.io.File

/** An entry of the lnreader plugin index. */
@Serializable
data class LnReaderPlugin(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val url: String,
    val iconUrl: String? = null,
)

/**
 * Installs lnreader plugins from the official index into `filesDir/lnreader-plugins`, one
 * `<id>.js` per plugin plus `installed.json` with their index entries.
 */
class LnReaderPlugins {

    private val app: Application by injectLazy()
    private val network: NetworkHelper by injectLazy()

    private val dir by lazy { File(app.filesDir, "lnreader-plugins").apply { mkdirs() } }
    private val installedFile get() = File(dir, "installed.json")

    fun installed(): List<LnReaderPlugin> =
        runCatching { json.decodeFromString<List<LnReaderPlugin>>(installedFile.readText()) }
            .getOrDefault(emptyList())

    fun source(plugin: LnReaderPlugin) = JsNovelSource(plugin, codeFile(plugin.id))

    suspend fun available(): List<LnReaderPlugin> = withIOContext {
        val body = network.client.newCall(GET(INDEX_URL)).awaitSuccess().body.string()
        json.decodeFromString<List<LnReaderPlugin>>(body).map { it.copy(lang = it.lang.cleanLang()) }
    }

    suspend fun install(plugin: LnReaderPlugin): JsNovelSource = withIOContext {
        val code = network.client.newCall(GET(plugin.url)).awaitSuccess().body.string()
        codeFile(plugin.id).writeText(code)
        save(installed().filterNot { it.id == plugin.id } + plugin.copy(lang = plugin.lang.cleanLang()))
        source(plugin)
    }

    fun uninstall(pluginId: String) {
        codeFile(pluginId).delete()
        save(installed().filterNot { it.id == pluginId })
    }

    private fun save(plugins: List<LnReaderPlugin>) {
        val tmp = File(dir, "installed.json.tmp")
        tmp.writeText(json.encodeToString(plugins))
        tmp.renameTo(installedFile)
    }

    private fun codeFile(pluginId: String) = File(dir, pluginId.replace(UNSAFE_FILE_CHARS, "_") + ".js")

    private companion object {
        const val INDEX_URL =
            "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"

        val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")

        val json = Json { ignoreUnknownKeys = true }

        /** Index languages are display names, some prefixed with a left-to-right mark. */
        fun String.cleanLang() = trim('‎', ' ')
    }
}
