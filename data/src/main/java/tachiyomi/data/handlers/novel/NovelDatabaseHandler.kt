package tachiyomi.data.handlers.novel

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import tachiyomi.novel.data.NovelDatabase

/**
 * Query entry point for [NovelDatabase], the third content database alongside the manga
 * `Database` and `AnimeDatabase`.
 *
 * Deliberately smaller than the anime handler: novel queries never need a DB-backed paging
 * source (catalogs are paged by the source, not by SQL), and transactions are plain SQLDelight
 * blocks dispatched to IO rather than suspending nested ones.
 */
class NovelDatabaseHandler(
    val db: NovelDatabase,
    private val queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun <T> await(inTransaction: Boolean = false, block: NovelDatabase.() -> T): T {
        return withContext(queryDispatcher) {
            if (inTransaction) db.transactionWithResult { block(db) } else block(db)
        }
    }

    suspend fun <T : Any> awaitList(
        inTransaction: Boolean = false,
        block: NovelDatabase.() -> Query<T>,
    ): List<T> = await(inTransaction) { block(db).executeAsList() }

    suspend fun <T : Any> awaitOne(
        inTransaction: Boolean = false,
        block: NovelDatabase.() -> Query<T>,
    ): T = await(inTransaction) { block(db).executeAsOne() }

    suspend fun <T : Any> awaitOneOrNull(
        inTransaction: Boolean = false,
        block: NovelDatabase.() -> Query<T>,
    ): T? = await(inTransaction) { block(db).executeAsOneOrNull() }

    fun <T : Any> subscribeToList(block: NovelDatabase.() -> Query<T>): Flow<List<T>> {
        return block(db).asFlow().mapToList(queryDispatcher)
    }

    fun <T : Any> subscribeToOne(block: NovelDatabase.() -> Query<T>): Flow<T> {
        return block(db).asFlow().mapToOne(queryDispatcher)
    }

    fun <T : Any> subscribeToOneOrNull(block: NovelDatabase.() -> Query<T>): Flow<T?> {
        return block(db).asFlow().mapToOneOrNull(queryDispatcher)
    }
}
