package com.streamiax.streaming.addon

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AddonClient @Inject constructor(
    private val httpClient: HttpClient,
) {
    suspend fun fetchManifest(baseUrl: String): AddonManifest =
        httpClient.get("$baseUrl/manifest.json").body()

    suspend fun fetchCatalog(
        baseUrl: String,
        type: String,
        id: String,
        extra: Map<String, String> = emptyMap(),
    ): CatalogResponse {
        val extraPath = if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") { "${it.key}=${it.value}" }
        return httpClient.get("$baseUrl/catalog/$type/$id$extraPath.json").body()
    }

    suspend fun fetchMeta(baseUrl: String, type: String, id: String): MetaResponse =
        httpClient.get("$baseUrl/meta/$type/$id.json").body()

    suspend fun fetchStreams(baseUrl: String, type: String, id: String): StreamResponse =
        httpClient.get("$baseUrl/stream/$type/$id.json").body()
}
