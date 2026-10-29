package tachiyomi.data.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.novel.data.NovelDatabase
import java.sql.SQLException

class NovelDatabaseMigrationTest {

    @Test
    fun `migration 1 merges duplicate novels, chapters and categories`() {
        val driver = v1Database()
        driver.exec(
            """
            INSERT INTO categories(_id, name, sort, flags) VALUES (1, 'Fav', 1, 0);
            ${novelRow(1, "a")};
            ${novelRow(2, "a", favorite = 1, author = "X", dateAdded = 50)};
            ${novelRow(3, "b")};
            ${chapterRow(10, novelId = 1, url = "c1", read = 1, position = 4)};
            ${chapterRow(11, novelId = 2, url = "c1", position = 9, dateFetch = 30)};
            ${chapterRow(12, novelId = 2, url = "c2", dateFetch = 40)};
            ${chapterRow(13, novelId = 2, url = "c2", bookmark = 1, dateFetch = 20)};
            ${chapterRow(14, novelId = 3, url = "c1")};
            INSERT INTO novelhistory(chapter_id, last_read, time_read) VALUES (11, 100, 5), (10, 200, 7);
            INSERT INTO novels_categories(novel_id, category_id) VALUES (1, 1), (2, 1);
            """,
        )

        NovelDatabase.Schema.migrate(driver, 1, 2)

        // The copy in the library stays and keeps its details
        assertEquals(
            listOf(listOf(2L, 1L, 50L), listOf(3L, 0L, 0L)),
            driver.longRows("SELECT _id, favorite, date_added FROM novels ORDER BY _id", 3),
        )
        // Copies of a chapter become one, with the read state, position and bookmark of any copy
        assertEquals(
            listOf(
                listOf(10L, 2L, 1L, 0L, 9L, 30L),
                listOf(12L, 2L, 0L, 1L, 0L, 20L),
                listOf(14L, 3L, 0L, 0L, 0L, 0L),
            ),
            driver.longRows(
                "SELECT _id, novel_id, read, bookmark, last_position, date_fetch FROM novel_chapters ORDER BY _id",
                6,
            ),
        )
        // Reading time adds up, the last read is the latest
        assertEquals(
            listOf(listOf(10L, 200L, 12L)),
            driver.longRows("SELECT chapter_id, last_read, time_read FROM novelhistory", 3),
        )
        assertEquals(
            listOf(listOf(2L, 1L)),
            driver.longRows("SELECT novel_id, category_id FROM novels_categories", 2),
        )
    }

    @Test
    fun `after migration 1 duplicates are rejected`() {
        val driver = v1Database()
        driver.exec("${novelRow(1, "a")}; ${chapterRow(1, novelId = 1, url = "c1")}")

        NovelDatabase.Schema.migrate(driver, 1, 2)

        assertThrows<SQLException> { driver.exec(novelRow(2, "a")) }
        assertThrows<SQLException> { driver.exec(chapterRow(2, novelId = 1, url = "c1")) }
    }

    @Test
    fun `schema version counts the migration`() {
        assertEquals(2L, NovelDatabase.Schema.version)
    }

    /** Version 1 had the same tables, without the unique indexes migration 1 adds. */
    private fun v1Database() = inMemoryNovelDriver(foreignKeys = false).also { driver ->
        NovelDatabase.Schema.create(driver)
        driver.exec(
            """
            DROP INDEX novels_source_url_unique;
            DROP INDEX novel_chapters_novel_url_unique;
            DROP INDEX novels_categories_unique
            """,
        )
    }
}
