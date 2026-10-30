package tachiyomi.data.novel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.data.handlers.novel.NovelDatabaseHandler
import tachiyomi.data.items.novelchapter.NovelChapterRepositoryImpl
import tachiyomi.domain.items.novelchapter.model.NovelChapter
import tachiyomi.domain.items.novelchapter.model.NovelChapterRemoteUpdate
import tachiyomi.novel.data.NovelDatabase
import java.sql.SQLException

class NovelChapterRepositoryImplTest {

    private val driver = inMemoryNovelDriver().also { NovelDatabase.Schema.create(it) }
    private val database = novelDatabase(driver)
    private val repository = NovelChapterRepositoryImpl(NovelDatabaseHandler(database, Dispatchers.Unconfined))

    @Test
    fun `updateFromRemote removes, adds and updates, skipping urls the novel has`() = runTest {
        driver.exec("${novelRow(1, "a")}; ${chapterRow(1, 1, "old")}; ${chapterRow(2, 1, "kept")}")

        val added = repository.updateFromRemote(
            removedIds = listOf(1),
            added = listOf(chapter(1, "kept"), chapter(1, "new")),
            updated = listOf(NovelChapterRemoteUpdate(id = 2, name = "renamed", sourceOrder = 0, chapterNumber = 1.0)),
        )

        assertEquals(listOf("new"), added.map { it.url })
        assertEquals(
            listOf("kept" to "renamed", "new" to "new"),
            repository.getChapterByNovelId(1).sortedBy { it.id }.map { it.url to it.name },
        )
    }

    @Test
    fun `a failing sync changes nothing`() = runTest {
        driver.exec("${novelRow(1, "a")}; ${chapterRow(1, 1, "c1")}")

        // Novel 99 does not exist, so the insert breaks the foreign key after the removal ran
        assertThrows<SQLException> {
            repository.updateFromRemote(
                removedIds = listOf(1),
                added = listOf(chapter(99, "x")),
                updated = emptyList(),
            )
        }

        assertEquals(listOf("c1"), repository.getChapterByNovelId(1).map { it.url })
    }

    @Test
    fun `history shows one row per novel when two chapters were read at the same time`() = runTest {
        driver.exec(
            """
            ${novelRow(1, "a", favorite = 1)};
            ${chapterRow(1, 1, "c1")};
            ${chapterRow(2, 1, "c2")};
            INSERT INTO novelhistory(chapter_id, last_read, time_read) VALUES (1, 100, 0), (2, 100, 0)
            """,
        )

        val recent = database.novelhistoryQueries.getRecent().executeAsList()

        assertEquals(listOf(1L to 2L), recent.map { it.novelId to it.chapterId })
    }

    @Test
    fun `deleting a novel deletes its chapters and history`() {
        driver.exec(
            """
            ${novelRow(1, "a")};
            ${chapterRow(1, 1, "c1")};
            INSERT INTO novelhistory(chapter_id, last_read, time_read) VALUES (1, 100, 0);
            DELETE FROM novels WHERE _id = 1
            """,
        )

        assertEquals(emptyList<List<Long?>>(), driver.longRows("SELECT _id FROM novel_chapters", 1))
        assertEquals(emptyList<List<Long?>>(), driver.longRows("SELECT _id FROM novelhistory", 1))
    }

    private fun chapter(novelId: Long, url: String) =
        NovelChapter.create().copy(novelId = novelId, url = url, name = url)
}
