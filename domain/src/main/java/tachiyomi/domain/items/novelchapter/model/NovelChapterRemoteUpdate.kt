package tachiyomi.domain.items.novelchapter.model

/**
 * What a sync with the source may change on a stored chapter, as Mihon's `ChapterRemoteUpdate`:
 * never the read state, bookmark or position, which belong to the user.
 */
data class NovelChapterRemoteUpdate(
    val id: Long,
    val name: String,
    val sourceOrder: Long,
    val chapterNumber: Double,
)
