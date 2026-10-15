# StreamiaX

StreamiaX is an Android media app built **on top of [Aniyomi](https://github.com/aniyomiorg/aniyomi)**.
It keeps every Aniyomi feature and adds two integrations:

- **Stremio** streaming add-ons (movies/series/torrents) exposed as a native anime source
- **lnreader**-style **light novels** as a new first-class source type alongside anime and manga

## Inherited from Aniyomi (all features intact)

- Anime watching with the built-in **mpv** player (subtitles, audio tracks, gestures, PiP)
- Manga reading with multiple viewers and reading directions
- Extension system (`source-api` / `animesource` APK extensions)
- Trackers: MyAnimeList, AniList, Kitsu, MangaUpdates, Shikimori, Simkl, Bangumi
- Library with categories, automated updates, and downloads for offline use
- Full backup/restore
- Material 3 UI, light/dark themes

## Added by StreamiaX

| Integration | Status | Approach |
|---|---|---|
| Stremio add-ons | in progress | Native `AnimeHttpSource` speaking the Stremio add-on HTTP protocol (catalog/meta/stream) |
| Light novels | in progress | New `novelsource` hierarchy mirroring `animesource`/`source`, with a dedicated text reader and DB tables; lnreader JS plugins install from the official index and run in QuickJS |

## Build

```bash
./gradlew assembleRelease
```

Produces split APKs under `app/build/outputs/apk/release/` (universal + per-ABI).
JDK 17 required. The mpv player ships as a prebuilt AAR (`aniyomi-mpv-lib`), so no NDK toolchain is needed.

### lnreader plugin host

`app/src/main/assets/lnreader/host.js` is hand-written; `libs.js` next to it is generated
(cheerio, htmlparser2, dayjs, URL) and committed, so Gradle needs no Node. To bump those
libraries or check the host against real plugins:

```bash
cd app/src/main/js/lnreader-libs
npm install
npm run build                      # regenerates assets/lnreader/libs.js
npm run smoke -- royalroad novelbin
```

## Releases

Push a tag matching `v*` to build, sign, and publish a GitHub Release with all ABI APKs:

```bash
git tag v0.2.0 -m "..." && git push origin v0.2.0
```

## Attribution and license

StreamiaX is a derivative work of Aniyomi, which is itself based on Mihon / Tachiyomi.
Licensed under the **Apache License 2.0** (see [LICENSE](LICENSE)). Original copyright and
notices are retained. This project is not affiliated with or endorsed by the Aniyomi,
Mihon, Tachiyomi, Stremio, or lnreader teams.

### Disclaimer

The developers of this application do not have any affiliation with the content providers
available, and the application does not host or distribute any content.
