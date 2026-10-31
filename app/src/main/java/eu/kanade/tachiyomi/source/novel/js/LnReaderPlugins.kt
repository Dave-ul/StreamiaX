package eu.kanade.tachiyomi.source.novel.js

import android.app.Application
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
 *
 * Like Cloudstream's RepositoryManager.downloadPluginToFile, files are written to a temporary file
 * and moved into place, so a failed download or a crash never leaves a broken plugin behind. Only
 * code from the lnreader-plugins repository is accepted: the index lists no checksums to verify.
 */
class LnReaderPlugins(
    private val dir: File = File(Injekt.get<Application>().filesDir, "lnreader-plugins"),
    private val download: suspend (url: String) -> String = ::httpGet,
) {

    private val mutex = Mutex()

    private val installedFile get() = File(dir, "installed.json")

    /**
     * The installed plugins. An `installed.json` that cannot be parsed is moved to
     * `installed.json.bak` (or a dated `.bak` when that exists) rather than overwritten by the next
     * install: the plugins it listed can be installed again, and their novels stay in the library
     * since source ids come from the plugin ids. A read error is thrown: the file may be fine
     * next time, so it is left where it is.
     */
    fun installed(): List<LnReaderPlugin> {
        if (!installedFile.exists()) return emptyList()
        val text = installedFile.readText()
        return try {
            json.decodeFromString<List<LnReaderPlugin>>(text)
        } catch (e: IllegalArgumentException) {
            // SerializationException included
            setAsideUnreadable(e)
        }
    }

    fun source(plugin: LnReaderPlugin) = JsNovelSource(plugin, codeFile(plugin.id))

    suspend fun available(): List<LnReaderPlugin> = withIOContext {
        json.decodeFromString<List<LnReaderPlugin>>(download(INDEX_URL)).map { it.copy(lang = it.lang.cleanLang()) }
    }

    /** Downloads [plugin] and records it as installed, replacing an older version. */
    suspend fun install(plugin: LnReaderPlugin) = withIOContext {
        require(isTrustedPluginUrl(plugin.url)) { "${plugin.name}: not an lnreader-plugins url: ${plugin.url}" }
        val code = download(plugin.url)
        mutex.withLock {
            // Read first: a failed read must not leave new code under the old entry
            val others = installed().filterNot { it.id == plugin.id }
            writeAtomically(codeFile(plugin.id), code)
            save(others + plugin.copy(lang = plugin.lang.cleanLang()))
        }
    }

    suspend fun uninstall(pluginId: String) = withIOContext {
        mutex.withLock {
            save(installed().filterNot { it.id == pluginId })
            codeFile(pluginId).delete()
        }
    }

    private fun setAsideUnreadable(e: Exception): List<LnReaderPlugin> {
        val bak = File(dir, "installed.json.bak").takeUnless { it.exists() }
            ?: File(dir, "installed.json.${System.currentTimeMillis()}.bak")
        logcat(LogPriority.ERROR, e) { "installed.json is unreadable, moved to ${bak.name}" }
        move(installedFile, bak)
        return emptyList()
    }

    private fun save(plugins: List<LnReaderPlugin>) = writeAtomically(installedFile, json.encodeToString(plugins))

    private fun writeAtomically(file: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        try {
            tmp.writeText(text)
            move(tmp, file)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun codeFile(pluginId: String) = File(dir, pluginId.replace(UNSAFE_FILE_CHARS, "_") + ".js")

    companion object {
        private const val INDEX_URL =
            "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"

        private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")

        private val json = Json { ignoreUnknownKeys = true }

        /** Index languages are display names, some prefixed with a left-to-right mark. */
        private fun String.cleanLang() = trim('‎', ' ')

        private suspend fun httpGet(url: String): String =
            Injekt.get<NetworkHelper>().client.newCall(GET(url)).awaitSuccess().body.string()

        /** The index points at raw files of the lnreader-plugins repository; GitHub owner names ignore case. */
        internal fun isTrustedPluginUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            val segments = parsed.pathSegments
            return parsed.isHttps &&
                parsed.host == "raw.githubusercontent.com" &&
                segments.size > 2 &&
                segments[0].equals("lnreader", ignoreCase = true) &&
                segments[1].equals("lnreader-plugins", ignoreCase = true)
        }
    }
}
