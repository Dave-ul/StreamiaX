package com.streamiax.streaming.catalog

import com.streamiax.domain.model.Chapter
import com.streamiax.domain.model.Episode
import com.streamiax.domain.model.MediaItem
import com.streamiax.domain.model.MediaSource
import com.streamiax.domain.model.MediaType
import com.streamiax.domain.model.StreamLink
import com.streamiax.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val stremio: StremioRepository,
) : MediaRepository {

    override fun search(query: String, type: MediaType?, source: MediaSource?): Flow<List<MediaItem>> =
        stremio.search(query, type)

    override fun catalog(source: MediaSource, type: MediaType, genre: String?): Flow<List<MediaItem>> =
        when (source) {
            MediaSource.Stremio -> stremio.catalog(type, genre)
            else -> emptyFlow()
        }

    override suspend fun detail(id: String, source: MediaSource): MediaItem {
        TODO("wire per-source detail fetch")
    }

    override suspend fun episodes(mediaId: String, source: MediaSource): List<Episode> =
        when (source) {
            MediaSource.Stremio -> stremio.episodes(mediaId, MediaType.Series)
            else -> emptyList()
        }

    override suspend fun chapters(mediaId: String, source: MediaSource): List<Chapter> = emptyList()

    override suspend fun streams(episodeId: String, source: MediaSource): List<StreamLink> =
        when (source) {
            MediaSource.Stremio -> stremio.streams(episodeId, MediaType.Movie)
            else -> emptyList()
        }

    override suspend fun pages(chapterId: String, source: MediaSource): List<String> = emptyList()
}
