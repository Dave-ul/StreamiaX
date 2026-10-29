package eu.kanade.tachiyomi.ui.novel

import eu.kanade.tachiyomi.novelsource.model.SNovelChapter
import tachiyomi.domain.items.chapter.service.ChapterRecognition
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
 * A new chapter with the number of a removed one takes over its read and bookmark state, so a
 * site that changes its urls does not reset progress (Mihon's SyncChaptersWithSource does the same).
 * Chapter numbers the plugin leaves out are read from the name with Aniyomi's
 * [ChapterRecognition], as the manga sync (SyncChaptersWithSource) and lnreader do.
 */
internal fun planChapterSync(
    novelId: Long,
    novelTitle: String,
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
        val number = ChapterRecognition.parseChapterNumber(
            novelTitle,
            chapter.name,
            chapter.chapter_number.toDouble(),
        )
        val existing = byUrl[chapter.url]
        if (existing == null) {
            toAdd += NovelChapter.create().copy(
                novelId = novelId,
                url = chapter.url,
                name = chapter.name,
                dateUpload = chapter.date_upload,
                chapterNumber = number,
                sourceOrder = order,
                dateFetch = now,
            )
        } else if (existing.name != chapter.name || existing.sourceOrder != order || existing.chapterNumber != number) {
            toUpdate += NovelChapterUpdate(
                id = existing.id,
                name = chapter.name,
                sourceOrder = order,
                chapterNumber = number,
            )
        }
    }
    val remoteUrls = unique.mapTo(HashSet()) { it.url }
    val removed = known.filter { it.url !in remoteUrls }
    return ChapterSyncPlan(carryOverState(toAdd, removed), toUpdate, removed.map { it.id })
}

private fun carryOverState(added: List<NovelChapter>, removed: List<NovelChapter>): List<NovelChapter> {
    val removedByNumber = removed.filter { it.isRecognizedNumber }.groupBy { it.chapterNumber }
    if (removedByNumber.isEmpty()) return added
    return added.map { chapter ->
        val previous = removedByNumber[chapter.chapterNumber]
        if (!chapter.isRecognizedNumber || previous == null) return@map chapter
        chapter.copy(
            read = previous.any { it.read },
            bookmark = previous.any { it.bookmark },
            // Keeps the chapter where it was in the list of new chapters, as Mihon does for Updates
            dateFetch = previous.maxOf { it.dateFetch },
        )
    }
}
