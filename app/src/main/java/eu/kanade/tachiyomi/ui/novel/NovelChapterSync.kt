package eu.kanade.tachiyomi.ui.novel

import eu.kanade.tachiyomi.novelsource.model.SNovelChapter
import tachiyomi.domain.items.novelchapter.model.NovelChapter
import tachiyomi.domain.items.novelchapter.model.NovelChapterUpdate

internal data class ChapterSyncPlan(
    val toAdd: List<NovelChapter>,
    val toUpdate: List<NovelChapterUpdate>,
    val toRemove: List<Long>,
)

/**
 * Diffs the source's chapter list against the stored one, matching by url: new chapters are
 * added, renamed or reordered ones updated (read state is kept), vanished ones removed.
 */
internal fun planChapterSync(
    novelId: Long,
    remote: List<SNovelChapter>,
    known: List<NovelChapter>,
    now: Long,
): ChapterSyncPlan {
    // An empty list is far more likely a broken source than a novel that lost every chapter.
    if (remote.isEmpty()) return ChapterSyncPlan(emptyList(), emptyList(), emptyList())

    val byUrl = known.associateBy { it.url }
    val toAdd = mutableListOf<NovelChapter>()
    val toUpdate = mutableListOf<NovelChapterUpdate>()
    val unique = remote.distinctBy { it.url }
    unique.forEachIndexed { index, chapter ->
        val order = index.toLong()
        val existing = byUrl[chapter.url]
        if (existing == null) {
            toAdd += NovelChapter.create().copy(
                novelId = novelId,
                url = chapter.url,
                name = chapter.name,
                dateUpload = chapter.date_upload,
                chapterNumber = chapter.chapter_number.toDouble(),
                sourceOrder = order,
                dateFetch = now,
            )
        } else if (existing.name != chapter.name || existing.sourceOrder != order) {
            toUpdate += NovelChapterUpdate(id = existing.id, name = chapter.name, sourceOrder = order)
        }
    }
    val remoteUrls = unique.mapTo(HashSet()) { it.url }
    val toRemove = known.filter { it.url !in remoteUrls }.map { it.id }
    return ChapterSyncPlan(toAdd, toUpdate, toRemove)
}
