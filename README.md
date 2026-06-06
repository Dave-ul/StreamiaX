# StreamiaX

Unified Android media app combining three open-source projects into one experience:

- **Streaming** - [stremio-core](https://github.com/Stremio/stremio-core) add-on protocol: movies, series, torrents
- **Anime/Manga** - [aniyomi](https://github.com/aniyomiorg/aniyomi) source/extension system
- **Light Novels** - [lnreader](https://github.com/lnreader/lnreader) plugin architecture (ported to Kotlin)

## Architecture

```
:app                      - Shell, navigation, DI wiring
:core:domain              - Shared models and repository interfaces
:core:network             - Ktor + OkHttp, Hilt module
:core:database            - Room database
:core:common              - Utilities
:core:streaming           - Stremio add-on HTTP protocol client + registry
:core:anime               - Aniyomi-compatible source interfaces + extension loader
:core:novel               - LNReader plugin interface (Kotlin port)
:feature:catalog          - Browse/search across all sources
:feature:player           - ExoPlayer/Media3 video player
:feature:reader           - Manga page reader + novel text reader
:feature:library          - Saved/tracked content
```

## Integration approach

| Project | Integration method |
|---|---|
| stremio-core | Stremio add-on HTTP protocol (no JNI needed; add-ons are remote HTTP servers) |
| aniyomi | Source interface mirrors `tachiyomi.source.AnimeSource`; loads extension APKs via PathClassLoader |
| lnreader | Plugin interface ported from JS contract to Kotlin; OkHttp + Jsoup for scraping |

## Requirements

- Android 8.0+ (minSdk 26)
- Android Studio Hedgehog or later
- JDK 17

## Build

```bash
./gradlew assembleDebug
```

## Stremio add-ons

Add-ons ship with two defaults (Cinemeta + Torrentio). Add more from settings by pasting any `manifest.json` URL.

## Aniyomi extensions

Install any aniyomi-compatible extension APK and it will be auto-detected by `ExtensionLoader` on next launch.

## Novel plugins

Native Kotlin plugins live in `:core:novel`. Implement `NovelPlugin` and register via `PluginRegistry`.
