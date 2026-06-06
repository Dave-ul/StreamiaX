package com.streamiax.domain.repository

import com.streamiax.domain.model.Chapter
import com.streamiax.domain.model.Episode
import com.streamiax.domain.model.MediaItem
import com.streamiax.domain.model.MediaSource
import com.streamiax.domain.model.MediaType
import com.streamiax.domain.model.StreamLink
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    fun search(query: String, type: MediaType? = null, source: MediaSource? = null): Flow<List<MediaItem>>
    fun catalog(source: MediaSource, type: MediaType, genre: String? = null): Flow<List<MediaItem>>
    suspend fun detail(id: String, source: MediaSource): MediaItem
    suspend fun episodes(mediaId: String, source: MediaSource): List<Episode>
    suspend fun chapters(mediaId: String, source: MediaSource): List<Chapter>
    suspend fun streams(episodeId: String, source: MediaSource): List<StreamLink>
    suspend fun pages(chapterId: String, source: MediaSource): List<String>
}
