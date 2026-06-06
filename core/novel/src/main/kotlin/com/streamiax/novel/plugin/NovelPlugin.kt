package com.streamiax.novel.plugin

import com.streamiax.novel.model.Novel
import com.streamiax.novel.model.NovelChapter
import com.streamiax.novel.model.NovelPage
import com.streamiax.novel.model.SearchResult

// Kotlin port of lnreader's JavaScript plugin contract.
// JS plugins from lnreader-plugins can run via a lightweight JS engine (QuickJS via Duktape)
// or be reimplemented natively here.
interface NovelPlugin {
    val id: String
    val name: String
    val lang: String
    val iconUrl: String?
    val version: String

    suspend fun popularNovels(page: Int): List<Novel>
    suspend fun latestNovels(page: Int): List<Novel>
    suspend fun searchNovels(query: String, page: Int): List<SearchResult>
    suspend fun parseNovelDetail(url: String): Novel
    suspend fun parseChapterList(novelUrl: String): List<NovelChapter>
    suspend fun parseChapter(chapterUrl: String): NovelPage
}
