package com.streamiax.anime.model

data class AnimeCatalogPage(
    val animes: List<AnimePreview>,
    val hasNextPage: Boolean,
)

data class AnimePreview(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
)

data class AnimeDetail(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val description: String?,
    val genres: List<String> = emptyList(),
    val status: Int = 0, // 0=unknown,1=ongoing,2=completed,3=licensed,4=publishing,5=cancelled,6=hiatus
    val author: String? = null,
)

data class AnimeEpisode(
    val url: String,
    val name: String,
    val episodeNumber: Float = -1f,
    val dateUpload: Long = 0L,
    val scanlator: String? = null,
)

data class AnimeVideo(
    val url: String,
    val quality: String,
    val videoUrl: String?,
    val headers: Map<String, String> = emptyMap(),
)

data class AnimeFilter(val name: String, val value: String)

// Manga models
data class MangaCatalogPage(
    val mangas: List<MangaPreview>,
    val hasNextPage: Boolean,
)

data class MangaPreview(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
)

data class MangaDetail(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val description: String?,
    val genres: List<String> = emptyList(),
    val status: Int = 0,
    val author: String? = null,
)

data class MangaChapter(
    val url: String,
    val name: String,
    val chapterNumber: Float = -1f,
    val dateUpload: Long = 0L,
    val scanlator: String? = null,
)

data class Page(
    val index: Int,
    val url: String,
    val imageUrl: String? = null,
)
