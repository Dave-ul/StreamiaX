package eu.kanade.tachiyomi.ui.novel

import eu.kanade.tachiyomi.novelsource.model.SNovelChapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.items.novelchapter.model.NovelChapter
import tachiyomi.domain.items.novelchapter.model.NovelChapterUpdate

class NovelChapterSyncTest {

    @Test
    fun `adds new, updates renamed or moved, removes vanished`() {
        val known = listOf(
            stored(id = 1, url = "/c1", name = "Chapter 1", order = 0, number = 1.0),
            stored(id = 2, url = "/c2", name = "Chapter 2", order = 1, number = 2.0),
            stored(id = 3, url = "/gone", name = "Old", order = 2),
        )
        val remote = listOf(
            remote("/c1", "Chapter 1"),
            remote("/new", "Chapter 1.5"),
            remote("/c2", "Chapter 2 (fixed)"),
        )

        val plan = planChapterSync(novelId = 9, novelTitle = "Novel", remote = remote, known = known, now = 42)

        assertEquals(listOf("/new"), plan.toAdd.map { it.url })
        assertEquals(1L, plan.toAdd.single().sourceOrder)
        assertEquals(9L, plan.toAdd.single().novelId)
        // No number from the plugin: recognized from the name, as Aniyomi does for manga.
        assertEquals(1.5, plan.toAdd.single().chapterNumber)
        assertEquals(
            listOf(NovelChapterUpdate(id = 2, name = "Chapter 2 (fixed)", sourceOrder = 2, chapterNumber = 2.0)),
            plan.toUpdate,
        )
        assertEquals(listOf(3L), plan.toRemove)
    }

    @Test
    fun `empty remote list changes nothing`() {
        val plan = planChapterSync(1, "Novel", emptyList(), listOf(stored(1, "/c1", "C1", 0)), now = 0)
        assertEquals(ChapterSyncPlan(emptyList(), emptyList(), emptyList()), plan)
    }

    @Test
    fun `a chapter back under a new url keeps its read and bookmark state`() {
        val known = listOf(
            stored(1, "/old-1", "Chapter 1", 0, number = 1.0).copy(read = true, bookmark = true, dateFetch = 5),
            stored(2, "/old-2", "Chapter 2", 1, number = 2.0),
        )
        val remote = listOf(remote("/new-1", "Chapter 1"), remote("/new-2", "Chapter 2"))

        val plan = planChapterSync(novelId = 9, novelTitle = "Novel", remote = remote, known = known, now = 42)

        assertEquals(
            listOf(Triple("/new-1", true, true), Triple("/new-2", false, false)),
            plan.toAdd.map { Triple(it.url, it.read, it.bookmark) },
        )
        assertEquals(5L, plan.toAdd.first().dateFetch)
        assertEquals(listOf(1L, 2L), plan.toRemove)
    }

    @Test
    fun `a url listed twice by the source is added once`() {
        val remote = listOf(remote("/c1", "Chapter 1"), remote("/c1", "Chapter 1"))

        val plan = planChapterSync(novelId = 9, novelTitle = "Novel", remote = remote, known = emptyList(), now = 0)

        assertEquals(listOf("/c1"), plan.toAdd.map { it.url })
    }

    private fun stored(id: Long, url: String, name: String, order: Long, number: Double = -1.0) =
        NovelChapter.create().copy(
            id = id,
            novelId = 9,
            url = url,
            name = name,
            sourceOrder = order,
            chapterNumber = number,
        )

    private fun remote(url: String, name: String) = SNovelChapter.create().also {
        it.url = url
        it.name = name
        it.chapter_number = -1f
    }
}
