package eu.kanade.tachiyomi.ui.browse.novel.migration

import eu.kanade.tachiyomi.ui.novel.NovelEntryLoader
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.novel.repository.NovelCategoryRepository
import tachiyomi.domain.entries.novel.model.Novel
import tachiyomi.domain.entries.novel.model.NovelUpdate
import tachiyomi.domain.entries.novel.repository.NovelRepository
import tachiyomi.domain.items.novelchapter.model.NovelChapter
import tachiyomi.domain.items.novelchapter.model.NovelChapterUpdate
import tachiyomi.domain.items.novelchapter.repository.NovelChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Moves (or copies) a library novel to another source, carrying over read chapters, bookmarks and
 * categories, like the anime and manga migration.
 */
class NovelMigrator(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val chapterRepository: NovelChapterRepository = Injekt.get(),
    private val categoryRepository: NovelCategoryRepository = Injekt.get(),
    private val loader: NovelEntryLoader = NovelEntryLoader(),
) {

    suspend fun migrate(
        old: Novel,
        targetSourceId: Long,
        targetUrl: String,
        targetTitle: String,
        replace: Boolean,
    ) {
        val target = loader.getOrInsert(targetSourceId, targetUrl, targetTitle)
        if (target.id == old.id) return
        val (_, newChapters) = loader.refresh(target)

        val updates = planReadMigration(chapterRepository.getChapterByNovelId(old.id), newChapters)
        if (updates.isNotEmpty()) chapterRepository.updateAll(updates)

        val categories = categoryRepository.getCategoriesByNovelId(old.id)
            .filterNot(Category::isSystemCategory)
            .map { it.id }
        novelRepository.setNovelCategories(target.id, categories)
        novelRepository.updateNovel(NovelUpdate(id = target.id, favorite = true, dateAdded = old.dateAdded))
        if (replace) novelRepository.updateNovel(NovelUpdate(id = old.id, favorite = false))
    }
}

/**
 * Chapters match by number when both sides know it, otherwise by name. Like Mihon, everything up
 * to the highest read number is read too, so a source that splits chapters differently still
 * resumes in the right place.
 */
internal fun planReadMigration(old: List<NovelChapter>, new: List<NovelChapter>): List<NovelChapterUpdate> {
    val byNumber = old.filter { it.isRecognizedNumber }.groupBy { it.chapterNumber }
    val byName = old.groupBy { it.name.normalized() }
    val maxReadNumber = old.filter { it.read && it.isRecognizedNumber }.maxOfOrNull { it.chapterNumber }

    return new.mapNotNull { chapter ->
        val matches = (if (chapter.isRecognizedNumber) byNumber[chapter.chapterNumber] else null)
            ?: byName[chapter.name.normalized()]
            ?: emptyList()
        val read = matches.any { it.read } ||
            (maxReadNumber != null && chapter.isRecognizedNumber && chapter.chapterNumber <= maxReadNumber)
        val bookmark = matches.any { it.bookmark }
        if ((read && !chapter.read) || (bookmark && !chapter.bookmark)) {
            NovelChapterUpdate(
                id = chapter.id,
                read = if (read && !chapter.read) true else null,
                bookmark = if (bookmark && !chapter.bookmark) true else null,
            )
        } else {
            null
        }
    }
}

private fun String.normalized() = trim().lowercase()
