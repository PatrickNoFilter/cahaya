> **Status note (2026-10-29):** historical v0.9 design doc. The player
> it describes has long since shipped and evolved — current releases
> are phone 1.11.0 and TV box 1.9.0 (see `README.md` changelog). Kept
> for the design rationale, not as a build guide.

# Cahaya v0.9: modern player, navigation, icons and subtitles

This change set rebuilds the player screen of the **bootstrap** app (`bootstrap/`, the Java build that
actually ships as the APKs). The look and behaviour are modelled on
[NuvioTV](https://github.com/NuvioMedia/NuvioTV)'s player.

> **Read this first: it has not been run on a device.** No Android build tools or TV hardware were available
> while writing it. It was compile-checked against the real Android 34 `android.jar`, its pure logic has
> 98 passing unit tests, and its icons were rendered and inspected, but nothing has been through
> `aapt2` or run on a TV box. See [What was and wasn't verified](#what-was-and-wasnt-verified) and
> [Please check on a device](#please-check-on-a-device).

## v0.9.9: subtitle fixes (from a device log)

Fixes made against the author's `908b2a14-mpv-playback.log` (Amlogic box, `vo=mediacodec_embed`, a 34-subtitle-track MKV).
What the log showed, and what changed:

| Log evidence | Meaning | Fix |
|---|---|---|
| Every seek logged `seeking stream N (sub) to nothing` for all 34 MKV subtitle streams, in all 10 seeks | No embedded track was ever selected. Only the downloaded online file (a separate `lavf` demuxer) was active. | Embedded selection is now explicit (`selectEmbedded`), and mpv's own auto-pick is mirrored into the UI (`syncSelectionFromMpv`). |
| 34 subtitle tracks in one MKV | Typical of a Blu-ray remux, where most tracks are **PGS bitmaps**. `mediacodec_embed` cannot draw bitmaps, and the overlay only draws text. | Choosing an image track no longer ticks something that can't display. It switches to the best online subtitle in that track's language, or searches for one and applies it when results arrive. |
| `sub-text ... was unavailable` right after each seek | mpv's `sub-text` property is briefly unavailable after a seek and exists only for the single track mpv has selected. | Online subtitles are now drawn from **cues we parse ourselves** (SRT / WebVTT / ASS, `CueParser`), looked up from `time-pos`. They don't depend on mpv at all, and work immediately after seeking. |
| (no subtitle diagnostics in the log) | Hard to tell what the app thought it had selected. | `CahayaSub:` lines are logged under the `mpv` tag, so they appear in the same capture. |

**The tick.** It used to be derived from mpv's `sid`, a track-id map and a download that might still be pending. It now comes from one value,
`SubtitleSelection` (OFF / EMBEDDED id / ONLINE url), set exactly when the user chooses. The tick moves at once on tap, the row shows
"Loading…" while downloading, and it moves back if the download fails. Auto-select mirrors what mpv itself picked, and "Auto-select: Off"
now really turns subtitles off.

New classes: `SubtitleCue`, `CueParser` (parse, time lookup, SDH filter), `SubtitleSelection`. `SubtitleOverlay` gained a CUE mode next to its
MPV mode. Delay applies to both (`sub-delay` for embedded, an offset for cues). Tests: `bootstrap/tests/run_tests.sh` (98 checks).

**Still unverified on device:** embedded *text* tracks (SRT/ASS inside the MKV) still rely on mpv's `sub-text`. Pick one and look for the line
`CahayaSub: mpv sub-text is delivering text (embedded path works)` in the next log. If it never appears for a text track, send that log.
Image (PGS) embedded subtitles remain undrawable on this renderer by design.

## What was wrong before

Found while reading the v0.8 source (`PlayerActivity.java`, `DetailsActivity.java`):

| Problem | Detail |
|---|---|
| Subtitles never reached the player | `DetailsActivity` put a `sub_url` extra on the intent. `PlayerActivity` never read it. No code path ever called `sub-add`. |
| The `CC` button did nothing useful | It only flipped `sub-visibility` on a player that had no subtitle tracks. |
| `vo=mediacodec_embed` cannot draw subtitles | mpv's `vo.rst` says subtitle rendering and OSD are unavailable with that driver. So subtitles were impossible with the old video output, whatever else was fixed. |
| Subtitle lookup blocked playback | Torrent playback waited up to 8 s for every addon, then passed only the first result. Direct (non-torrent) streams never looked up subtitles. |
| Navigation used text glyphs | `◀ ◀◀ ❚❚ ▶▶ CC` in `Button`s with a flat blue focus fill. Rendering depends on the device font. |
| Duration formatter | `fmt()` printed `83:45` for a 1h23m film. |

## What changed

| File | Change |
|---|---|
| `bootstrap/src/.../ui/PlayerActivity.java` | **Rewritten.** New control layer, side panels, subtitle engine, key handling. |
| `bootstrap/src/.../ui/DetailsActivity.java` | Torrent path now launches the player immediately with the real `video_id`. The blocking `findSubtitles()` was removed. |
| `bootstrap/src/.../ui/player/*.java` | **New package**, 12 classes (table below). |
| `bootstrap/res/drawable/ic_player_*.xml` | **New**, 13 vector icons. |
| `bootstrap/tests/` | **New**, JVM unit tests (`run_tests.sh`). |
| `bootstrap/build.sh` | Output renamed to `Cahaya-v0.9.apk`, versionCode 10, versionName 0.9.0. Nothing else touched. |

New classes in `ui/player/`:

| Class | Role | Android-free (unit-tested) |
|---|---|---|
| `PlayerButton` | Round icon button: translucent idle, solid white + scale-up on focus, accent when active | no |
| `SeekTrack` | Seek bar. Thin, grows on focus, D-pad scrub with acceleration, deferred commit, touch drag | no |
| `SidePanel` | Right-hand picker panel with focus locked inside, rows, steppers, section headers | no |
| `MpvTrack` | Reads mpv `track-list` for audio and subtitle tracks, builds labels | no |
| `SubtitlePrefs` | Persisted style and renderer settings, pushes them to mpv | no |
| `SubtitleLoader` | Downloads a subtitle with Android's HTTP/TLS stack, normalises to UTF-8, caches it | no |
| `PlayerTheme` | Colour constants | no |
| `LangUtil` | `en`/`eng`/`fre`/`pt-BR` to one canonical code, plus English display names | **yes** |
| `ScrubRates` | Hold-to-accelerate seek steps (10/20/30/60 s), same tiers as NuvioTV | **yes** |
| `TimeFmt` | `1:23:45` clock and `+1.5 s` delay labels | **yes** |
| `SubtitleRanker` | De-dupe by URL, preferred language first, alphabetical after | **yes** |
| `SubtitleDecoder` | BOM / UTF-16 / strict UTF-8 / language-based legacy code page | **yes** |

## The new player

**Layout** (all programmatic, no layout XML, as before):

- Top scrim: back button and title.
- Bottom scrim: seek bar, elapsed and total time, then an icon row.
  - Left group: play/pause, back 10 s, forward 10 s.
  - Right group: subtitles, audio, speed, aspect.
- Controls fade in and out, and stay up while paused. A spinner shows while buffering (`paused-for-cache`).
- Pickers slide in from the right (`SidePanel`), the same pattern NuvioTV uses for its track panels.

**Icons**: 13 stroke-style vector drawables on a 24 dp grid, tinted at runtime. Preview of the exact path data:
`docs/icons-preview.png`.

**Remote / keys**

| State | Key | Action |
|---|---|---|
| Controls hidden | OK / Enter | Play/pause |
| Controls hidden | Left / Right | Scrub (hold to accelerate), show controls |
| Controls hidden | Up / Down | Show controls |
| Controls visible | D-pad | Normal focus movement. Seek bar: Left/Right scrub and commit after 450 ms. |
| Any | Play, Pause, Play/Pause, FF, Rewind | Handled |
| Any | `CAPTIONS` or `MENU` | Opens the subtitle panel |
| Any | Back | Closes the panel, then hides the controls, then exits |

**Audio, speed, aspect**: audio tracks from mpv's list (language, codec, channels). Speed 0.5×–2×. Aspect cycles Fit / Zoom / Stretch.

## Subtitles

1. **Tracks**: embedded and external tracks come from mpv's `track-list` (language, title, codec, Forced / Default / External flags).
2. **Online search**: on launch the player queries every installed addon (`/subtitles/{type}/{video_id}.json`) and every enabled plugin in the background. Playback starts immediately and the panel shows "Searching your addons…" until results arrive. Results are de-duplicated, the preferred language is listed first, and the first 30 are shown with a "Show N more" row.
3. **Loading a subtitle**: the file is downloaded in Java (`SubtitleLoader`), not by mpv, and handed over as a local file with `sub-add … cached`. Two reasons:
   - mpv's TLS on Android has no CA bundle configured here (NuvioTV ships a `cacert.pem` for this), so `https://` subtitle hosts could fail certificate checks.
   - Many `.srt` files are not UTF-8. `SubtitleDecoder` detects UTF-8, UTF-16 and BOMs, and otherwise falls back by language (Arabic→1256, Russian→1251, Turkish→1254, Greek→1253, Polish/Czech/Hungarian→1250, Chinese→GB18030, Japanese→Shift_JIS, Korean→EUC-KR, default 1252).

   The loader also follows redirects manually, handles gzip, caps size at 6 MB, caches for 7 days, and upgrades non-local `http://` to `https://` (the app's network security config allows cleartext only to localhost).
4. **Auto-select**: preferred language defaults to the device language (fallback English) and is changeable in *Appearance → Auto-select*, including Off. Order: mpv's own `slang` pick, then an embedded track in that language (non-forced first), then the best online match, added as soon as it arrives.
5. **Delay**: ±100 ms steps from −180 s to +180 s, Enter resets. Applied through mpv's `sub-delay`.
6. **Appearance** (persisted in `SharedPreferences` `cahaya_player`): size 50–200 %, height, 6 colours, bold, outline, background box, hide-SDH, and the renderer toggle. Property names follow NuvioTV's `applySubtitleStyle`: `sub-scale`, `sub-bold`, `sub-outline-size`, `sub-color`, `sub-pos`, `sub-border-style`, `sub-back-color`, `sub-shadow-offset`, `sub-filter-sdh`.
7. The legacy `sub_url` intent extra is still honoured, as one more online candidate.

### Renderer (current state)

The first version of this change defaulted to `vo=gpu`. On the target Mali B866F box that faults, so upstream reverted to
`vo=mediacodec_embed` (v0.9.2) and draws subtitles itself in `SubtitleOverlay` (v0.9.3 onward). That is the design now. The GPU-renderer
toggle was removed; mpv never draws subtitles in this app.

## Intent contract (unchanged apart from semantics)

`url` (required), `title`, `type`, `video_id`, optional `start_pos` (seconds), optional legacy `sub_url`.
For torrents, `video_id` used to be sent as `""`. It is now the real id so online subtitle search works.

## What was and wasn't verified

Done:

- **Compile**: all 81 classes compile with `javac --release 17` against the real Android 34 `android.jar`
  (from `Reginer/aosp-android-jar`). A stub `R.java` generated from `res/` stood in for `aapt2`'s. Exit code 0.
- **Unit tests**: `bootstrap/tests/run_tests.sh`, 98 checks on `LangUtil`, `ScrubRates`, `TimeFmt`, `SubtitleRanker`, `CueParser`, `SubtitleSelection`
  and `SubtitleDecoder`. This caught one real bug (French showed as "FRA"), now fixed.
- **Icons**: the 13 path definitions were rendered as SVG in Chromium and checked by eye. The XML parses.
- **Source review**: control flow was walked through against NuvioTV's mpv code for property names and `sub-add`.

**Not** done: running `build.sh` (needs the author's SDK, JDK and keystore paths), `aapt2` compilation of the
vector drawables, `d8`, or any run on a TV or emulator. The UI layout, focus behaviour and mpv calls are unproven at runtime.

## Please check on a device

1. **Embedded text subtitle shows.** Pick an SRT/ASS track under *In this video*. The next log should contain
   `CahayaSub: mpv sub-text is delivering text (embedded path works)`. If it never appears, send that log.
2. **Online subtitle shows, follows seeks, and respects Delay.** It is drawn from parsed cues, so it should not blink out after a seek.
3. **The tick**: moves the instant you tap, shows "Loading…" while downloading, goes back if the download fails, and is on exactly one row (Off, one embedded track, or one online entry).
4. **Image (PGS) track**: tapping one should keep the old tick and switch to the matching online subtitle (or say none was found).
5. **Log lines**: `adb logcat -s mpv` and look for `CahayaSub:`. At file load it dumps every subtitle track with its codec and whether it is an image.
6. **Focus feel**: D-pad order (back ↔ seek ↔ buttons), panel focus lock, and the 4.5 s auto-hide.
7. **Seeking on torrent streams** relies on the local HTTP server honouring range requests.

## Not changed

- `app/` (the Kotlin/Gradle skeleton, `PlaybackActivity.kt`) is untouched. The README in `bootstrap/` says it is the future build, but the shipping app is the bootstrap, so the work went there.
- Stremio addon protocol code, plugin host, torrent streamer, `MPVLib` JNI bridge, manifest.
- Native libs and jars are not in this zip (see below).

## Building and testing

```bash
# pure-logic tests, JDK only
bootstrap/tests/run_tests.sh

# full APK: needs your Android SDK/JDK/keystore paths from bootstrap/build.sh
bootstrap/build.sh        # -> bootstrap/Cahaya-v0.9.apk
```

This zip omits large unchanged binaries (`bootstrap/jniLibs/`, `bootstrap/libs/`, old APKs, `bootstrap/artwork/`,
`.git`) to stay small. To build, copy those directories from the upstream repo
(`github.com/PatrickNoFilter/cahaya`) into `bootstrap/`.

`changes.patch` (next to this folder in the zip) is the full `git diff` against upstream `HEAD`, for review.
