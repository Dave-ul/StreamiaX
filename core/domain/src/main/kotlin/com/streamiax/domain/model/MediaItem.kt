package com.streamiax.domain.model

import java.time.Instant

data class MediaItem(
    val id: String,
    val title: String,
    val description: String?,
    val posterUrl: String?,
    val bannerUrl: String?,
    val type: MediaType,
    val source: MediaSource,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val rating: Float? = null,
    val status: ContentStatus = ContentStatus.Unknown,
)

enum class MediaType { Movie, Series, Anime, Manga, Novel }

enum class MediaSource { Stremio, Aniyomi, LNReader }

enum class ContentStatus { Ongoing, Completed, Hiatus, Unknown }

data class Episode(
    val id: String,
    val mediaId: String,
    val title: String,
    val number: Float,
    val season: Int? = null,
    val thumbnailUrl: String? = null,
    val uploadedAt: Instant? = null,
    val watched: Boolean = false,
)

data class Chapter(
    val id: String,
    val mediaId: String,
    val title: String,
    val number: Float,
    val uploadedAt: Instant? = null,
    val read: Boolean = false,
    val pageCount: Int? = null,
)

data class StreamLink(
    val url: String,
    val quality: String?,
    val title: String?,
    val behaviorHints: BehaviorHints = BehaviorHints(),
)

data class BehaviorHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val filename: String? = null,
)
