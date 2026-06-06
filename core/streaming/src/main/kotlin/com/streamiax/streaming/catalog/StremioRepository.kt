package com.streamiax.streaming.catalog

import com.streamiax.domain.model.ContentStatus
import com.streamiax.domain.model.Episode
import com.streamiax.domain.model.MediaItem
import com.streamiax.domain.model.MediaSource
import com.streamiax.domain.model.MediaType
import com.streamiax.domain.model.StreamLink
import com.streamiax.streaming.addon.AddonClient
import com.streamiax.streaming.addon.AddonRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StremioRepository @Inject constructor(
    private val addonClient: AddonClient,
    private val addonRegistry: AddonRegistry,
) {
    fun catalog(type: MediaType, genre: String? = null): Flow<List<MediaItem>> = flow {
        val stremioType = type.toStremioType()
        val results = addonRegistry.getAddonsForResource("catalog", stremioType).flatMap { addon ->
            addon.manifest.catalogs
                .filter { it.type == stremioType }
                .flatMap { spec ->
                    val extra = if (genre != null) mapOf("genre" to genre) else emptyMap()
                    runCatching {
                        addonClient.fetchCatalog(addon.baseUrl, stremioType, spec.id, extra).metas
                    }.getOrDefault(emptyList())
                }
        }
        emit(results.map { it.toMediaItem() })
    }

    fun search(query: String, type: MediaType?): Flow<List<MediaItem>> = flow {
        val stremioType = type?.toStremioType() ?: "movie"
        val results = addonRegistry.getAddonsForResource("catalog", stremioType).flatMap { addon ->
            addon.manifest.catalogs
                .filter { it.type == stremioType && it.extra.any { e -> e.name == "search" } }
                .flatMap { spec ->
                    runCatching {
                        addonClient.fetchCatalog(addon.baseUrl, stremioType, spec.id, mapOf("search" to query)).metas
                    }.getOrDefault(emptyList())
                }
        }
        emit(results.map { it.toMediaItem() })
    }

    suspend fun streams(videoId: String, type: MediaType): List<StreamLink> {
        val stremioType = type.toStremioType()
        return addonRegistry.getAddonsForResource("stream", stremioType).flatMap { addon ->
            runCatching {
                addonClient.fetchStreams(addon.baseUrl, stremioType, videoId).streams.mapNotNull { s ->
                    val url = s.url ?: return@mapNotNull null
                    StreamLink(
                        url = url,
                        quality = s.name,
                        title = s.title,
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    suspend fun episodes(mediaId: String, type: MediaType): List<Episode> {
        val stremioType = type.toStremioType()
        val addon = addonRegistry.getAddonsForResource("meta", stremioType).firstOrNull() ?: return emptyList()
        val meta = runCatching { addonClient.fetchMeta(addon.baseUrl, stremioType, mediaId).meta }.getOrNull()
        return meta?.videos?.mapIndexed { i, v ->
            Episode(
                id = v.id,
                mediaId = mediaId,
                title = v.title,
                number = v.episode?.toFloat() ?: (i + 1).toFloat(),
                season = v.season,
                thumbnailUrl = v.thumbnail,
            )
        } ?: emptyList()
    }
}

private fun com.streamiax.streaming.addon.MetaPreview.toMediaItem() = MediaItem(
    id = id,
    title = name,
    description = description,
    posterUrl = poster,
    bannerUrl = background,
    type = type.toMediaType(),
    source = MediaSource.Stremio,
    genres = genres ?: emptyList(),
    status = ContentStatus.Unknown,
)

private fun String.toMediaType() = when (this) {
    "movie" -> MediaType.Movie
    "series" -> MediaType.Series
    "anime" -> MediaType.Anime
    else -> MediaType.Movie
}

private fun MediaType.toStremioType() = when (this) {
    MediaType.Movie -> "movie"
    MediaType.Series -> "series"
    MediaType.Anime -> "series"
    else -> "movie"
}
