package com.streamiax.anime.source

import com.streamiax.anime.model.AnimeCatalogPage
import com.streamiax.anime.model.AnimeDetail
import com.streamiax.anime.model.AnimeEpisode
import com.streamiax.anime.model.AnimeFilter
import com.streamiax.anime.model.AnimeVideo

// Mirrors the interface contract from aniyomi's source-api module.
// Compatible with aniyomi extensions loaded as APKs (same package/method names).
interface AnimeSource {
    val id: Long
    val name: String
    val lang: String
    val supportsLatest: Boolean

    suspend fun getPopularAnime(page: Int): AnimeCatalogPage
    suspend fun getLatestUpdates(page: Int): AnimeCatalogPage
    suspend fun searchAnime(page: Int, query: String, filters: List<AnimeFilter>): AnimeCatalogPage
    suspend fun getAnimeDetails(url: String): AnimeDetail
    suspend fun getEpisodeList(url: String): List<AnimeEpisode>
    suspend fun getVideoList(episode: AnimeEpisode): List<AnimeVideo>
}

interface MangaSource {
    val id: Long
    val name: String
    val lang: String
    val supportsLatest: Boolean

    suspend fun getPopularManga(page: Int): com.streamiax.anime.model.MangaCatalogPage
    suspend fun getLatestUpdates(page: Int): com.streamiax.anime.model.MangaCatalogPage
    suspend fun searchManga(page: Int, query: String, filters: List<AnimeFilter>): com.streamiax.anime.model.MangaCatalogPage
    suspend fun getMangaDetails(url: String): com.streamiax.anime.model.MangaDetail
    suspend fun getChapterList(url: String): List<com.streamiax.anime.model.MangaChapter>
    suspend fun getPageList(chapter: com.streamiax.anime.model.MangaChapter): List<com.streamiax.anime.model.Page>
}
