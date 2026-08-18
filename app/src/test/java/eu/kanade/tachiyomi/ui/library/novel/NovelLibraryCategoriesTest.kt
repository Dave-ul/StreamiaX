package eu.kanade.tachiyomi.ui.library.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.novel.model.Novel
import tachiyomi.domain.library.novel.LibraryNovel

class NovelLibraryCategoriesTest {

    private val default = category(Category.UNCATEGORIZED_ID, "", order = -1)
    private val reading = category(1, "Reading", order = 0)

    @Test
    fun `no tabs when the user has no categories of their own`() {
        assertEquals(
            emptyList<Category>(),
            libraryCategoryTabs(listOf(default), listOf(novelIn(Category.UNCATEGORIZED_ID))),
        )
    }

    @Test
    fun `default category is kept while it still holds novels`() {
        assertEquals(
            listOf(default, reading),
            libraryCategoryTabs(
                listOf(default, reading),
                listOf(novelIn(Category.UNCATEGORIZED_ID), novelIn(reading.id)),
            ),
        )
    }

    @Test
    fun `default category is dropped once every novel is categorized`() {
        assertEquals(
            listOf(reading),
            libraryCategoryTabs(listOf(default, reading), listOf(novelIn(reading.id))),
        )
    }

    private fun category(id: Long, name: String, order: Long) =
        Category(id = id, name = name, order = order, flags = 0, hidden = false)

    private fun novelIn(categoryId: Long) = LibraryNovel(
        novel = Novel.create(),
        category = categoryId,
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )
}
