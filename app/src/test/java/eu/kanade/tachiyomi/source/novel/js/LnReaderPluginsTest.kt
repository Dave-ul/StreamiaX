package eu.kanade.tachiyomi.source.novel.js

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class LnReaderPluginsTest {

    @TempDir
    lateinit var dir: File

    private val files = mutableMapOf<String, String>()

    private val plugins by lazy {
        LnReaderPlugins(dir) { url -> files[url] ?: throw IOException("HTTP 404 for $url") }
    }

    @Test
    fun `install keeps the plugins already installed`() = runTest {
        plugins.install(plugin("a"))
        plugins.install(plugin("b"))

        assertEquals(listOf("a", "b"), plugins.installed().map { it.id })
        assertEquals("code of a", File(dir, "a.js").readText())
    }

    @Test
    fun `reinstalling replaces the entry and the code`() = runTest {
        plugins.install(plugin("a"))
        files[url("a")] = "new code of a"
        plugins.install(plugin("a", version = "2.0.0"))

        assertEquals(listOf("2.0.0"), plugins.installed().map { it.version })
        assertEquals("new code of a", File(dir, "a.js").readText())
    }

    @Test
    fun `a failed download leaves the installed plugin untouched`() = runTest {
        plugins.install(plugin("a"))
        // plugin() puts the code back for a missing url, so the update is made before removing it
        val update = plugin("a", version = "2.0.0")
        files.remove(url("a"))

        assertThrows<IOException> { plugins.install(update) }

        assertEquals(listOf("1.0.0"), plugins.installed().map { it.version })
        assertEquals("code of a", File(dir, "a.js").readText())
        assertEquals(listOf("a.js", "installed.json"), dir.list()!!.sorted())
    }

    @Test
    fun `an unreadable installed json is set aside, not overwritten`() = runTest {
        File(dir, "installed.json").writeText("[{\"id\": \"a\", trunca")

        assertEquals(emptyList<LnReaderPlugin>(), plugins.installed())
        plugins.install(plugin("b"))

        assertEquals("[{\"id\": \"a\", trunca", File(dir, "installed.json.bak").readText())
        assertEquals(listOf("b"), plugins.installed().map { it.id })
    }

    @Test
    fun `a second unreadable installed json does not overwrite the first set aside`() = runTest {
        File(dir, "installed.json").writeText("first broken")
        plugins.installed()
        File(dir, "installed.json").writeText("second broken")
        plugins.installed()

        assertEquals("first broken", File(dir, "installed.json.bak").readText())
        val dated = dir.listFiles()!!.single { it.name.matches(Regex("installed\\.json\\.\\d+\\.bak")) }
        assertEquals("second broken", dated.readText())
    }

    @Test
    fun `an installed json that cannot be read is left in place`() = runTest {
        // A directory exists but cannot be read as a file
        File(dir, "installed.json").mkdirs()

        assertThrows<IOException> { plugins.installed() }
        assertThrows<IOException> { plugins.install(plugin("a")) }
        assertTrue(File(dir, "installed.json").isDirectory)
        assertEquals(listOf("installed.json"), dir.list()!!.sorted())
    }

    @Test
    fun `uninstall removes the entry and the code`() = runTest {
        plugins.install(plugin("a"))
        plugins.install(plugin("b"))
        plugins.uninstall("a")

        assertEquals(listOf("b"), plugins.installed().map { it.id })
        assertFalse(File(dir, "a.js").exists())
    }

    @Test
    fun `code from outside the lnreader-plugins repository is refused`() = runTest {
        val evil = plugin("a").copy(url = "https://example.com/a.js")
        files[evil.url] = "evil code"

        assertThrows<IllegalArgumentException> { plugins.install(evil) }
        assertFalse(File(dir, "a.js").exists())
    }

    @Test
    fun `trusted urls are https raw files of lnreader-plugins`() {
        val trusted = "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/.js/src/a.js"
        assertTrue(LnReaderPlugins.isTrustedPluginUrl(trusted))
        assertTrue(LnReaderPlugins.isTrustedPluginUrl(trusted.replace("/lnreader/", "/LNReader/")))
        assertFalse(LnReaderPlugins.isTrustedPluginUrl(trusted.replace("https:", "http:")))
        assertFalse(LnReaderPlugins.isTrustedPluginUrl(trusted.replace("lnreader-plugins", "other")))
        assertFalse(LnReaderPlugins.isTrustedPluginUrl(trusted.replace("/lnreader/", "/someone/")))
        assertFalse(
            LnReaderPlugins.isTrustedPluginUrl(
                "https://raw.githubusercontent.com.evil.com/lnreader/lnreader-plugins/a",
            ),
        )
        assertFalse(LnReaderPlugins.isTrustedPluginUrl("not a url"))
    }

    private fun url(id: String) =
        "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/$id.js"

    private fun plugin(id: String, version: String = "1.0.0"): LnReaderPlugin {
        files.putIfAbsent(url(id), "code of $id")
        return LnReaderPlugin(id, id, "https://$id.example", "English", version, url(id))
    }
}
