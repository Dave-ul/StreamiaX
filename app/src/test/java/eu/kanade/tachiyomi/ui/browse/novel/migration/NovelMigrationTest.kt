package eu.kanade.tachiyomi.ui.browse.novel.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.items.novelchapter.model.NovelChapter
import tachiyomi.domain.items.novelchapter.model.NovelChapterUpdate

class NovelMigrationTest {

    @Test
    fun `read state follows chapter numbers and covers everything up to the last read one`() {
        val old = listOf(
            chapter(1, number = 1.0, read = true),
            chapter(2, number = 3.0, read = true),
            chapter(3, number = 4.0, bookmark = true),
        )
        val new = listOf(
            chapter(11, number = 1.0),
            chapter(12, number = 2.0), // missing on the old source, but below the last read number
            chapter(13, number = 3.0),
            chapter(14, number = 4.0),
            chapter(15, number = 5.0),
        )

        assertEquals(
            listOf(
                NovelChapterUpdate(id = 11, read = true),
                NovelChapterUpdate(id = 12, read = true),
                NovelChapterUpdate(id = 13, read = true),
                NovelChapterUpdate(id = 14, bookmark = true),
            ),
            planReadMigration(old, new),
        )
    }

    @Test
    fun `unnumbered chapters match by name`() {
        val old = listOf(chapter(1, name = " Prologue ", read = true))
        val new = listOf(chapter(2, name = "prologue"), chapter(3, name = "Epilogue"))

        assertEquals(listOf(NovelChapterUpdate(id = 2, read = true)), planReadMigration(old, new))
    }

    private fun chapter(
        id: Long,
        number: Double = -1.0,
        name: String = "Chapter $id",
        read: Boolean = false,
        bookmark: Boolean = false,
    ) = NovelChapter.create().copy(id = id, chapterNumber = number, name = name, read = read, bookmark = bookmark)
}
