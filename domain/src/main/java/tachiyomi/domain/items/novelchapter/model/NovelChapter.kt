package tachiyomi.domain.items.novelchapter.model

data class NovelChapter(
    val id: Long,
    val novelId: Long,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPosition: Long,
    val dateFetch: Long,
    val sourceOrder: Long,
    val url: String,
    val name: String,
    val dateUpload: Long,
    val chapterNumber: Double,
) {
    val isRecognizedNumber: Boolean
        get() = chapterNumber >= 0f

    fun copyFrom(other: NovelChapter): NovelChapter {
        return copy(
            name = other.name,
            url = other.url,
            dateUpload = other.dateUpload,
            chapterNumber = other.chapterNumber,
        )
    }

    companion object {
        fun create() = NovelChapter(
            id = -1,
            novelId = -1,
            read = false,
            bookmark = false,
            lastPosition = 0,
            dateFetch = 0,
            sourceOrder = 0,
            url = "",
            name = "",
            dateUpload = -1,
            chapterNumber = -1.0,
        )
    }
}
