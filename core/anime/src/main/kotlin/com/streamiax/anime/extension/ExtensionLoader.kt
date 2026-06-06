package com.streamiax.anime.extension

import android.content.Context
import android.content.pm.PackageManager
import com.streamiax.anime.source.AnimeSource
import com.streamiax.anime.source.MangaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// Extension loader compatible with aniyomi extension APK format.
// Extensions declare themselves via metadata in their AndroidManifest:
//   <meta-data android:name="tachiyomi.animeextension" android:value="..." />
private const val ANIME_EXTENSION_FEATURE = "tachiyomi.animeextension"
private const val MANGA_EXTENSION_FEATURE = "tachiyomi.extension"
private const val EXTENSION_CLASS_KEY = "tachiyomi.extension.class"

@Singleton
class ExtensionLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun loadAnimeSources(): List<AnimeSource> {
        val pm = context.packageManager
        return pm.getInstalledPackages(PackageManager.GET_META_DATA)
            .filter { pkg -> pkg.reqFeatures?.any { it.name == ANIME_EXTENSION_FEATURE } == true }
            .mapNotNull { pkg ->
                val className = pkg.applicationInfo?.metaData?.getString(EXTENSION_CLASS_KEY) ?: return@mapNotNull null
                runCatching {
                    val loader = pm.getApplicationInfo(pkg.packageName, 0)
                    val classLoader = context.classLoader
                    classLoader.loadClass(className).getDeclaredConstructor().newInstance() as? AnimeSource
                }.getOrNull()
            }
    }

    fun loadMangaSources(): List<MangaSource> {
        val pm = context.packageManager
        return pm.getInstalledPackages(PackageManager.GET_META_DATA)
            .filter { pkg -> pkg.reqFeatures?.any { it.name == MANGA_EXTENSION_FEATURE } == true }
            .mapNotNull { pkg ->
                val className = pkg.applicationInfo?.metaData?.getString(EXTENSION_CLASS_KEY) ?: return@mapNotNull null
                runCatching {
                    classLoader(pm, pkg.packageName, context)?.loadClass(className)
                        ?.getDeclaredConstructor()?.newInstance() as? MangaSource
                }.getOrNull()
            }
    }

    private fun classLoader(pm: PackageManager, packageName: String, context: Context): ClassLoader? {
        val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return null
        return dalvik.system.PathClassLoader(appInfo.sourceDir, context.classLoader)
    }
}
