# Cahaya

An Android TV media-center app built from scratch — in the spirit of Stremio,
Nuvio and CloudStream — with support for community addons and plugins.

> **Cahaya** (Indonesian/Malay for "light", from Sanskrit छाया *chāyā* via
> Old Javanese *cahya*) — name decided 2026-10-21 after a collision check
> (StreamBox taken, Evestream too close to EVStream).

## The idea

Cahaya hosts **no content**. It is a client shell that talks to addons:

1. **Stremio addon protocol** (phase 1–2, the big win): any addon that exposes
   a `manifest.json` works — catalog, meta, stream and subtitle resources over
   plain HTTPS. Verified live 2026-10-21 against
   `v3-cinemeta.strem.io` and `opensubtitles-v3.strem.io`:
   - `GET {transport}/manifest.json` → id, version, resources[], types[],
     idPrefixes[], catalogs[]
   - `GET {transport}/catalog/{type}/{id}.json[?search=&skip=&genre=]`
     → `{metas: [...], hasMore, ...}` (follows HTTP redirects)
   - `GET {transport}/meta/{type}/{id}.json` → `{meta: {...}}`
   - `GET {transport}/stream/{type}/{videoId}.json` → `{streams: [{url, title, behaviorHints}]}`
   - `GET {transport}/subtitles/{type}/{videoId}.json` → `{subtitles: [{id, url, lang}]}`
   - resources[] entries may be strings (`"catalog"`) or objects
     (`{name, types, idPrefixes}`); routing matches on type + id prefix.

   Day one this gives the app the entire Stremio addon ecosystem
   (Torrentio, MediaFusion, OpenSubtitles, …) with zero new plugin format.

2. **Native plugin SDK** (phase 3): a small host API for things the Stremio
   protocol can't express — custom scrapers, UI plugins, CloudStream-style
   per-site extensions. Original design, see `plugin/`.

## Layout

```
app/src/main/java/com/patrisius/cahaya/
  addon/          # Stremio-protocol engine: manifest model, HTTP client, manager
  model/          # MediaItem, Episode, StreamSource, SubtitleTrack
  plugin/         # Phase-3 native plugin host (stub)
  ui/             # Leanback TV UI skeleton + Media3 playback
```

## Roadmap

- [x] Phase 0 — protocol verified live, project scaffolded
- [ ] Phase 1 — addon engine complete + JVM-tested against real addons
- [ ] Phase 2 — Leanback browse/details UI, Media3 playback with subtitles,
      library + watch-state, addon manager screen (install via URL / QR)
- [ ] Phase 3 — native plugin SDK + sample plugin
- [ ] Phase 4 — phone companion / multiplatform (his call)

## Building

Gradle project. First build needs network for dependencies
(google() + mavenCentral()). The `forge` InstaBox machine has the Android SDK
and JDK; the manual aapt2/javac/d8 pipeline in `~/workspace/tvhome-build`
is the fallback if Gradle misbehaves in this sandbox.

```
cd ~/workspace/cahaya
gradle :app:assembleDebug        # or ./gradlew once the wrapper jar is added
```

`local.properties` with `sdk.dir=` is gitignored — point it at your SDK.

## Legal posture

Client only: no content hosted, served or stored. All code written from
scratch for this project — nothing copied from Nuvio/Stremio/CloudStream.
Note: Nuvio is GPL-3.0; if Nuvio code is ever borrowed, this whole app must
be GPL-3.0 too. Currently: author's choice, no license file yet.
