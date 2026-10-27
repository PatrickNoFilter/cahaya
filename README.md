# Cahaya

An Android TV media-center app built from scratch — in the spirit of Stremio,
Nuvio and CloudStream — with support for community addons and plugins.

> **Cahaya** (Indonesian/Malay for "light", from Sanskrit छाया *chāyā* via
> Old Javanese *cahya*) — name decided 2026-10-21 after a collision check
> (StreamBox taken, Evestream too close to EVStream).

## Releases

Current version: **1.6.3** (build 80). Release APKs are published on the
[Releases page](https://github.com/PatrickNoFilter/cahaya/releases) — one
release per version, each carrying its own APK.

Install: sideload the APK on an Android TV box (armeabi-v7a, Android 11+).
The APK is ~93 MB because it bundles the libmpv player and the in-app
torrent engine. No Play Store release yet.

### Changelog

- **1.6.3** — Golden frame redrawn as the card's foreground (above all
  children), so no edge can be eaten by thumbnail or padding
  interaction; audio panel labels a track with neither language nor
  title as "Audio N" (position, not mpv id) instead of "Unknown".
- **1.6.2** — Card strips get vertical breathing room and no clipping,
  so a focused card's scaled gold frame is never cut at the top or
  bottom (includes the 1.6.1 padding fix, which never shipped on its
  own); player log now appends across sessions instead of overwriting
  each player start; audio click prefers the row's own track id when
  the fresh track list still carries it.
- **1.6.0** — Audio: a failed track pick restores the previously
  playing track instead of muting (with a second check at 3.8 s for
  slow torrent switches); aspect Fit/Stretch swapped per correction
  and aspect read via string properties; golden frame on focused body
  cards (Playing History, trending, search).
- **1.5.8** — Aspect values accepted only in the sane 0.2–5.0 range
  (garbage numeric reads could size the surface 0 px wide = black
  picture); audio auto-fallback removed — a failed pick only logs and
  hints, never mutes.
- **1.5.7** — Audio: the pick is trusted on set and verified 800 ms
  later instead of read back immediately (mpv switches tracks
  asynchronously, so the immediate read always saw "no" and clobbered
  the pick); aspect diagnostics logged to the player log.
- **1.5.6** — Sidebar/body gap deleted (body padding 0 in both sidebar
  states); audio mute guard: no readable track id leaves the current
  track untouched instead of disabling audio; aspect applied by
  resizing the SurfaceView (mpv aspect properties have no visible
  effect on the mediacodec_embed surface).
- **1.5.5** — Stream-picker columns are sized to their content (capped
  at an equal share) with exactly the same gap between every pair of
  columns; the row is centered, so leftover space splits equally to
  both edges.
- **1.5.4** — Stream-picker options are uniform: every option is exactly
  two lines (title + info: seeders/size/host for torrents, host
  otherwise), same height and spacing in every column, rows aligned
  across columns.
- **1.5.3** — Picker columns condensed to equal-weight columns in a
  near-full-width dialog so all columns are visible at once; Next
  button fixed: picking a stream no longer exits to Home (mpv END_FILE
  for the outgoing file is now ignored while a switch is in flight).
- **1.5.2** — Stream-picker options are words only (no button
  rectangles); the focused word turns blue.
- **1.5.1** — Stream picker rebuilt as columns (not rows) in PlayIMDb,
  Torrentio, 4khdhub order, plus an "Other" column when other sources
  have streams; applied to Details Play, player Next, and Playing
  History clicks. Playing History keeps one entry per title: a newer
  episode replaces the older one, an older rewatch leaves the newer
  entry untouched.
- **1.5.0** — Stream picker grouped into source rows (PlayIMDb first,
  Torrentio second, 4khdhub third) in Details Play and player Next;
  Next and History paths fixed for `tmdb:` ids (right-anchored
  season/episode parse, meta routed by id prefix, scraper id derived
  from the `tmdb:` id).
- **1.4.0** — Home rows are filtered: only titles Cinemeta or TMDB can
  back with a picture and info stay in the list; unresolvable titles
  drop out and lower-ranked titles move up to fill the row.
  Chinese-titled entries resolve to their English title via TMDB /
  Cinemeta search.
- **1.3.1** — Cinemeta alternative: TMDB addon fallback for titles
  Cinemeta lacks (accepted only on an unambiguous exact match); hero
  and Details meta route by id prefix; refresh-overwrite race fixed
  so synced pictures survive a row rebuild.
- **1.3.0** — Trending rows sync with Cinemeta: cards swap poster and
  name in place to their real Cinemeta entry (exact-name match only);
  synced cards open Details directly; hero shows real meta.
- **1.2.0** — Home rebuilt: all old movie/series rows removed; Home is
  now Playing History + five own trending rows (Korean, Japanese,
  Chinese, Hollywood, Hollywood Movies) merged keylessly from 20
  sources.
- **1.1.3** — Back from any non-Home tab returns to Home first; second
  Back on Home exits.
- **1.1.2** — Search history also records on result click (even partial
  words), guarded to the Search tab.
- **1.1.1** — Search tab: "Recent searches" chip row (last 10 queries, most
  recent first) + live search as you type (500 ms debounce; live searches
  don't flood history or steal input focus).
- **1.1.0** — Player: Next-episode button right of Play (series only; pick
  a stream for the next episode, plays in place). Playing History: finished
  titles kept with a "Selesai" badge (replay restarts from 0); progress
  saved live every 30 s and on pause, not only on exit.
- **1.0.3** — Player controller buttons act on key-DOWN, so subtitle /
  audio / speed panels open on one press.
- **1.0.2** — Pause fix: atomic mpv `cycle pause`, OK / Space / P toggle,
  play button focused when the controller shows.
- **1.0.1** — Folded sidebar rail narrowed 76 → 56 dp, body padding
  28 → 16 dp while folded.
- **1.0.0** — Unfocused sidebar rows (selected or not) are transparent with
  a grey label, like the other tabs; only the focused row is highlighted.

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

- [x] Phase 0 — Stremio protocol verified live, project scaffolded
- [x] Phase 1 — addon engine complete, verified live against real addons
      (Cinemeta catalog/meta/search, Torrentio, MediaFusion, OpenSubtitles,
      SubDL, Subsource; redirect-following; string- and object-form
      `resources[]` entries)
- [x] Phase 2 — Leanback browse/details UI, playback with subtitles,
      library + watch-state, addon manager screen (install via URL / QR)
      - Home rows, hero banner with disk cache + background refresh,
        blurred app background, transparent sidebar, live Cinemeta search
        with search history
      - Details + stream picker (addons, plugins, in-app scrapers, torrents)
      - Playback via libmpv (replaced the Media3 skeleton:
        `vo=mediacodec_embed` on the Mali-G31 box) with self-drawn online
        subtitles, audio/speed/aspect panels, Next-episode button, Playing
        History with live saving
      - In-app torrent streaming (libtorrent4j, 20 MB preload, localhost
        HTTP with Range support) and WebView JS scrapers (4khdhub, PlayIMDb)
      - Addon install via QR + phone web flow; plugin host (HTTP `plugin.json`
        contract) with an install-by-URL Plugins screen
- [ ] Phase 3 — native plugin SDK + sample plugin (in progress: the HTTP
      plugin host and the WebView JS scraper runtime already ship; a formal
      SDK with a sample plugin is still open)
- [ ] Phase 4 — phone companion / multiplatform (his call)

Proposed, only on his word:

- Player error screen with Retry instead of a silent exit on mpv END_FILE
- Torrent rolling-deadline window (beyond first-8 + last-2 pieces)
- `behaviorHints` parsing from addon streams
- Hosting for the AIO-Cahaya adapter somewhere the box can reach
  (currently moot — the in-app scrapers cover its providers)

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
