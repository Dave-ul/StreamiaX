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
            stored(id = 1, url = "/c1", name = "Chapter 1", order = 0),
            stored(id = 2, url = "/c2", name = "Chapter 2", order = 1),
            stored(id = 3, url = "/gone", name = "Old", order = 2),
        )
        val remote = listOf(
            remote("/c1", "Chapter 1"),
            remote("/new", "Chapter 1.5"),
            remote("/c2", "Chapter 2 (fixed)"),
        )

        val plan = planChapterSync(novelId = 9, remote = remote, known = known, now = 42)

        assertEquals(listOf("/new"), plan.toAdd.map { it.url })
        assertEquals(1L, plan.toAdd.single().sourceOrder)
        assertEquals(9L, plan.toAdd.single().novelId)
        assertEquals(
            listOf(NovelChapterUpdate(id = 2, name = "Chapter 2 (fixed)", sourceOrder = 2)),
            plan.toUpdate,
        )
        assertEquals(listOf(3L), plan.toRemove)
    }

    @Test
    fun `empty remote list changes nothing`() {
        val plan = planChapterSync(1, emptyList(), listOf(stored(1, "/c1", "C1", 0)), now = 0)
        assertEquals(ChapterSyncPlan(emptyList(), emptyList(), emptyList()), plan)
    }

    private fun stored(id: Long, url: String, name: String, order: Long) =
        NovelChapter.create().copy(id = id, novelId = 9, url = url, name = name, sourceOrder = order)

    private fun remote(url: String, name: String) = SNovelChapter.create().also {
        it.url = url
        it.name = name
    }
}
