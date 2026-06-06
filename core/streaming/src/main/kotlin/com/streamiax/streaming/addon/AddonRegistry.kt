package com.streamiax.streaming.addon

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledAddon(
    val baseUrl: String,
    val manifest: AddonManifest,
)

// Default community add-ons that ship with the app
private val DEFAULT_ADDONS = listOf(
    "https://v3-cinemeta.strem.io",
    "https://torrentio.strem.fun",
)

@Singleton
class AddonRegistry @Inject constructor(
    private val addonClient: AddonClient,
) {
    private val mutex = Mutex()
    private val addons = mutableListOf<InstalledAddon>()
    private var initialized = false

    suspend fun initialize() {
        mutex.withLock {
            if (initialized) return
            DEFAULT_ADDONS.forEach { url ->
                runCatching { install(url) }
            }
            initialized = true
        }
    }

    suspend fun install(baseUrl: String): InstalledAddon {
        val manifest = addonClient.fetchManifest(baseUrl)
        val addon = InstalledAddon(baseUrl, manifest)
        mutex.withLock {
            addons.removeAll { it.manifest.id == manifest.id }
            addons.add(addon)
        }
        return addon
    }

    fun getAll(): List<InstalledAddon> = addons.toList()

    fun getAddonsForResource(resource: String, type: String): List<InstalledAddon> =
        addons.filter { addon ->
            addon.manifest.resources.any { r -> r == resource || r.contains(resource) } &&
                addon.manifest.types.contains(type)
        }
}
