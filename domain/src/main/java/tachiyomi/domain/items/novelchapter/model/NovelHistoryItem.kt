package tachiyomi.domain.items.novelchapter.model

import java.util.Date

/** The latest read chapter of a novel, as listed by the History screen. */
data class NovelHistoryItem(
    val novelId: Long,
    val chapterId: Long,
    val title: String,
    val thumbnailUrl: String?,
    val source: Long,
    val novelUrl: String,
    val chapterName: String,
    val readAt: Date?,
)
