package tachiyomi.data.novel

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import datanovel.Novelhistory
import datanovel.Novels
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.novel.data.NovelDatabase
import java.util.Properties

/** The app turns foreign keys on when it opens the database, and leaves them off while migrating. */
internal fun inMemoryNovelDriver(foreignKeys: Boolean = true): SqlDriver =
    JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { if (foreignKeys) put("foreign_keys", "true") },
    )

/** Built with the same adapters as in AppModule. */
internal fun novelDatabase(driver: SqlDriver) = NovelDatabase(
    driver = driver,
    novelhistoryAdapter = Novelhistory.Adapter(last_readAdapter = DateColumnAdapter),
    novelsAdapter = Novels.Adapter(
        genreAdapter = StringListColumnAdapter,
        update_strategyAdapter = MangaUpdateStrategyColumnAdapter,
    ),
)

internal fun SqlDriver.exec(sql: String) {
    sql.split(";").map(String::trim).filter(String::isNotEmpty).forEach { execute(null, it, 0) }
}

/** Rows of a query whose columns are all integers. */
internal fun SqlDriver.longRows(sql: String, columns: Int): List<List<Long?>> =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val rows = mutableListOf<List<Long?>>()
            while (cursor.next().value) rows += (0 until columns).map { cursor.getLong(it) }
            QueryResult.Value(rows)
        },
        parameters = 0,
    ).value

/** Rows of a query read as text, for names and schema statements. */
internal fun SqlDriver.stringRows(sql: String, columns: Int): List<List<String?>> =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val rows = mutableListOf<List<String?>>()
            while (cursor.next().value) rows += (0 until columns).map { cursor.getString(it) }
            QueryResult.Value(rows)
        },
        parameters = 0,
    ).value

internal fun novelRow(id: Long, url: String, favorite: Int = 0, author: String? = null, dateAdded: Long = 0): String {
    val authorValue = author?.let { "'$it'" } ?: "NULL"
    return "INSERT INTO novels(_id, source, url, author, title, status, favorite, initialized, reader_flags, " +
        "chapter_flags, cover_last_modified, date_added) " +
        "VALUES ($id, 7, '$url', $authorValue, 'T', 0, $favorite, $favorite, 0, 0, 0, $dateAdded)"
}

internal fun chapterRow(
    id: Long,
    novelId: Long,
    url: String,
    read: Int = 0,
    bookmark: Int = 0,
    position: Long = 0,
    dateFetch: Long = 0,
) = "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_position, chapter_number, " +
    "source_order, date_fetch, date_upload) " +
    "VALUES ($id, $novelId, '$url', '$url', $read, $bookmark, $position, 1, 0, $dateFetch, 0)"
