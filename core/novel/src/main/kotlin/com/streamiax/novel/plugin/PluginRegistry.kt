package com.streamiax.novel.plugin

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginRegistry @Inject constructor() {
    private val plugins = mutableMapOf<String, NovelPlugin>()

    fun register(plugin: NovelPlugin) {
        plugins[plugin.id] = plugin
    }

    fun get(id: String): NovelPlugin? = plugins[id]

    fun getAll(): List<NovelPlugin> = plugins.values.toList()

    fun getByLang(lang: String): List<NovelPlugin> = plugins.values.filter { it.lang == lang }
}
