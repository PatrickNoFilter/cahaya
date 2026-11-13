# Cahaya

An Android TV media-center app built from scratch — in the spirit of Stremio,
Nuvio and CloudStream — with support for community addons and plugins.

> **Cahaya** (Indonesian/Malay for "light", from Sanskrit छाया *chāyā* via
> Old Javanese *cahya*) — name decided 2026-10-21 after a collision check
> (StreamBox taken, Evestream too close to EVStream).

## Releases

One codebase, one universal APK per build (arm64-v8a + armeabi-v7a,
TV + phone UI auto-detected). Current release: **1.37.3** (build
147) — the same build runs on the phone and on the TV box.

Install: phone — download the APK and tap it (My Files → Internal
storage → Download). Box — sideload with
`adb install --no-incremental -r Cahaya-vX.Y.Z.apk` (plain
incremental install reports Success without installing a ~111 MB
APK). The APK is ~111 MB because it bundles the libmpv player, the
in-app torrent engine and a bundled Python + yt-dlp for YouTube
live channels. No Play Store release yet, and no APK is attached to
GitHub Releases — builds are distributed by sideload.

### Changelog

- **1.37.3** — Anti-block hardening: MovieBox file requests now carry
  the official MovieBox app User-Agent (phone + TV) instead of the
  app's own UA, so the file CDN sees traffic identical to the real
  app; API calls retry 429/5xx with backoff (1 s, 2 s, 3 attempts).
  (The bcdn file CDN 428s any Mozilla/5.0 UA — found 2026-11-13.)
- **1.37.2** — One D-pad press shows a whole Home row — list title +
  cards — at once (the Home ScrollView now scrolls the row's header
  to the top under the pinned hero); the merged MovieBox picker
  column orders phone-backend streams above MovieBox TV.
- **1.37.1** — Movies play again: the bcdn file CDN was rejecting the
  Chrome Mobile User-Agent (HTTP 428), so MovieBox file requests now
  carry a non-browser UA; MovieBox + MovieBox TV merged into one
  picker column. (PlayIMDb's backend returns no `stream_urls` for
  any title — upstream, its column hides itself until it recovers.)
- **1.37.0** — Now-playing flash shows the elapsed time ticking per
  second ("● Now Playing - 1:05:00").
- **1.36.4** — Now-playing flash re-fills when the episode title
  arrives; episode rows use the unified "S1E8 - Episode Title"
  format.
- **1.36.3** — One title format everywhere (player navigation, pause
  screensaver, now-playing flash): "S1E8 - Series - Episode Title"
  (flash title bold); video codec now contains-matched, so FFmpeg's
  long track name ("H.264 / AVC / MPEG-4 AVC / MPEG-4 PART 10")
  collapses to "H.264".
- **1.36.0–1.36.2** — Now-playing flash: small top-left card while
  watching, 30 s every 5 min — title, episode ("S1 E8 · Episode
  Title"), live video resolution + format and audio codec/channels/
  sample-rate; pulsing accent dot + bold "Now Playing" header.
- **1.35.1** — Dead-server memory (7-day TTL; a dead server is skipped
  when a live alternative exists); `|Referer=` URL suffixes in
  playlists are now parsed into the server's headers instead of being
  passed to mpv as a malformed URL.
- **1.35.0** — Live TV: the working server is remembered per channel
  (persisted by URL); loading animation (spinner + "Channel ·
  server x/y"); riotryulianto playlists added — ~204 unique channels.
- **1.34.4** — Next-episode subtitle carry-over: the in-player Next
  switch now passes the picked stream's subtitles (episode 3 lost
  its subs on the switch).
- **1.34.0–1.34.3** — MovieBox's own subtitles in the player:
  captions fetched per stream (phone + TV backends), attached to
  every stream, merged into the picker; confirmed working on
  Sunshine Through the Rain.
- **1.33.0–1.33.1** — Live TV round-robin across servers (8 s
  watchdog, YouTube fallback via bundled yt-dlp); YouTube audio fix
  (audio attaches via `audio-add`, not the comma-split `audio-files`
  option).
- **1.32.0–1.32.1** — Live TV tab (iptv-org Indonesia playlist + 7
  YouTube live channels); `ytdl=no` (mpv's ytdl_hook has no yt-dlp)
  + TvLog diagnostics.
- **1.31.0–1.31.1** — Irama (Lyra Music TV port) integration: Music
  tile + now-playing card bring Irama forward; a video auto-pauses
  it; Addons/Plugins moved into Settings, sidebar logo restored.
- **1.30.x** — Now-playing card, Music tile, `<queries>` fix for
  Irama.
- **1.29.0** — PosterUtil OOM fix (a 388 MB bitmap allocation crashed
  the app); MovieBox Trending merged as the first home row.
- **1.26.4** — MovieBox detail fallback (series Cinemeta lacks, e.g.
  Alas Roban with 24 episodes).
- **1.25.0** — MovieBox H5 backend (movie-box.co).
- **1.24.0** — WuPlay ideas (including the player navigation status
  line: resolution, codec, audio, bitrate).
- **1.23.3** — bcdnt CDN Referer fix (streams 429'd when a Referer
  header was sent).
- **1.22.0** — MovieBox TV backend (tv.aoneroom.com).
- **1.21.x** — MovieBox phone backend (api3.aoneroom.com, signed API).
- **1.20.0** — Refresh (home refresh).
- **1.12.0–1.19.1** — IMDb rows, auto-translate, pause screensaver
  (1.17.0: 10 s of pause → info screensaver), HDR10+ guard +
  software-decode fallback (1.18.0; root cause was a SIGSEGV in
  system libstagefright on HDR10+ HEVC), bitmap subtitles via native
  FFmpeg bridge (1.19.0), HDR10+ SEI probe (1.19.1).
- **1.11.0** — Settings → Display: banner auto-change 2–10 s;
  background Blur picture / Solid (black, blue, yellow, green, red);
  Settings Version row now reads from the package (it was a hardcoded
  string); Player row fixed (it still said "System player — coming
  in v0.3"; the app ships the in-app libmpv player); top bar shows
  "Cahaya vX.Y.Z" beside the menu button.
- **1.10.0** — Phone: adaptive banner returns as an auto-rotating
  worldwide banner (cycles the whole worldwide list; TV banner stays
  cursor-driven); top menu strip replaced by a top-left ☰ button +
  hidden side drawer (closes on button, body tap, or menu pick).
- **1.9.0** — Trending Worldwide sources replaced: Netflix Tudum
  global charts (4 categories) + Prime worldwide Originals (films +
  series combined lists) + Disney+ via FlixPatrol world today + Viu
  Indonesia via FlixPatrol aggregate; TMDB trending + IMDb dropped
  from worldwide only (Hollywood rows unchanged).
- **1.8.0** — Player menu glitch third pass (control bar fades out
  over 150 ms on panel open); first Home row Trending Worldwide
  (TMDB trending + IMDb moviemeter + IMDb TV at that time).
- **1.7.2** — Panel fixes: focus lands after the slide completes,
  stale-fade guard, outside-tap dismiss, subtitle Adjust section
  moved to top, appearance steppers work on touch.
- **1.7.1** — Phone: adaptive banner removed on phones only (later
  brought back as the rotating banner in 1.10.0); player tap toggles
  the control panel.
- **1.7.0** — Phone UI in the same APK: portrait layout, top menu
  strip (later replaced by the drawer in 1.10.0), selected-tab
  highlight, 160dp history cards, vertical Details, stacked
  stream-picker columns; TV layout untouched.
- **1.6.4** — Frame clip chain completed: every ancestor of a card
  strip no longer clips, and strips are padded 12dp on all sides, so
  the first/last cards' frames are complete too (row titles inset to
  match); track lists are now read entirely through mpv's string
  getter, since the numeric/boolean getters return garbage ids.
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
bootstrap/src/com/patrisius/cahaya/
  addon/          # Stremio-protocol engine: manifest model, HTTP client, manager
  plugin/         # Phase-3 native plugin host
  ui/             # TV + phone UI, player, stream picker, panels
  torrent/        # In-app torrent streaming (libtorrent4j + localhost HTTP)
  jsrunner/       # WebView JS scraper runtime (4khdhub, PlayIMDb)
  scraper/        # In-app site scrapers (e.g. FibWatch)
  qr/ web/        # QR + phone web addon-install flow
```

(The Gradle/Kotlin scaffold in `app/` is a stub and does not build
here; everything above is the shipping code.)

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
- [x] Phase 4 — phone UI in the same APK (since 1.7.0: phone vs TV
      auto-detected, portrait layout, drawer, rotating banner)
- [x] Phase 5 — MovieBox backends (phone 1.21, TV 1.22, H5 1.25) with
      MovieBox's own subtitles (1.34); Live TV (1.32–1.35: IPTV
      playlists + YouTube live channels, round-robin, remembered and
      dead-server memory); Irama music integration (1.31); now-playing
      flash + unified display title (1.36)

Proposed, only on his word:

- Player error screen with Retry instead of a silent exit on mpv END_FILE
- Torrent rolling-deadline window (beyond first-8 + last-2 pieces)
- `behaviorHints` parsing from addon streams
- Hosting for the AIO-Cahaya adapter somewhere the box can reach
  (currently moot — the in-app scrapers cover its providers)

## Building

Shipped builds come from the manual pipeline in `bootstrap/` —
Gradle's daemon cannot run in this sandbox, and the Gradle/Kotlin
project in `app/` is a stub that does not build here.

```
cd bootstrap
./build.sh   # aapt2 → javac → d8 → zipalign → apksigner
```

`build.sh` sets the version (APK name, version-code, version-name).
The signing keystore `keystore/cahaya.jks` is gitignored — keep it
safe, reuse for updates. The in-app Settings version label and the
phone top-bar title read the version from the package, so they track
every bump on their own.

## Legal posture

Client only: no content hosted, served or stored. All code written from
scratch for this project — nothing copied from Nuvio/Stremio/CloudStream.
Note: Nuvio is GPL-3.0; if Nuvio code is ever borrowed, this whole app must
be GPL-3.0 too. Currently: author's choice, no license file yet.
