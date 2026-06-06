package com.streamiax.novel.model

data class Novel(
    val url: String,
    val title: String,
    val coverUrl: String?,
    val summary: String? = null,
    val author: String? = null,
    val genres: List<String> = emptyList(),
    val status: NovelStatus = NovelStatus.Unknown,
    val source: String,
)

data class NovelChapter(
    val url: String,
    val title: String,
    val chapterNumber: Float = -1f,
    val dateUpload: Long = 0L,
    val read: Boolean = false,
)

data class NovelPage(
    val content: String, // HTML or plain text
    val isHtml: Boolean = true,
)

data class SearchResult(
    val url: String,
    val title: String,
    val coverUrl: String?,
    val source: String,
)

enum class NovelStatus { Ongoing, Completed, Hiatus, Unknown }
