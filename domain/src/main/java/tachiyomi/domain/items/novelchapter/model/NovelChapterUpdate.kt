package tachiyomi.domain.items.novelchapter.model

data class NovelChapterUpdate(
    val id: Long,
    val novelId: Long? = null,
    val read: Boolean? = null,
    val bookmark: Boolean? = null,
    val lastPosition: Long? = null,
    val dateFetch: Long? = null,
    val sourceOrder: Long? = null,
    val url: String? = null,
    val name: String? = null,
    val dateUpload: Long? = null,
    val chapterNumber: Double? = null,
)

fun NovelChapter.toChapterUpdate(): NovelChapterUpdate {
    return NovelChapterUpdate(
        id = id,
        novelId = novelId,
        read = read,
        bookmark = bookmark,
        lastPosition = lastPosition,
        dateFetch = dateFetch,
        sourceOrder = sourceOrder,
        url = url,
        name = name,
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
    )
}
