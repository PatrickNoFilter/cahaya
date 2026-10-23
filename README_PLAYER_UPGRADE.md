# Cahaya v0.9: modern player, navigation, icons and subtitles

This change set rebuilds the player screen of the **bootstrap** app (`bootstrap/`, the Java build that
actually ships as the APKs). The look and behaviour are modelled on
[NuvioTV](https://github.com/NuvioMedia/NuvioTV)'s player.

> **Read this first: it has not been run on a device.** No Android build tools or TV hardware were available
> while writing it. It was compile-checked against the real Android 34 `android.jar`, its pure logic has
> 54 passing unit tests, and its icons were rendered and inspected, but nothing has been through
> `aapt2` or run on a TV box. See [What was and wasn't verified](#what-was-and-wasnt-verified) and
> [Please check on a device](#please-check-on-a-device).

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

### Renderer change (important)

Subtitles need mpv's GPU output. v0.8 used `vo=mediacodec_embed`, with a code comment saying it was chosen to
avoid Mali GPU faults. The new default is therefore **`vo=gpu`** with `hwdec=mediacodec,mediacodec-copy`
and `gpu-context=android`, the same family of options NuvioTV uses. If a device faults, the **GPU renderer**
toggle (*Subtitles → Appearance*) switches live to the old direct path, at the cost of no subtitles. It is
persisted and also reachable from the panel's warning row. Check this on the Mali box first (see below).

## Intent contract (unchanged apart from semantics)

`url` (required), `title`, `type`, `video_id`, optional `start_pos` (seconds), optional legacy `sub_url`.
For torrents, `video_id` used to be sent as `""`. It is now the real id so online subtitle search works.

## What was and wasn't verified

Done:

- **Compile**: all 73 classes compile with `javac --release 17` against the real Android 34 `android.jar`
  (from `Reginer/aosp-android-jar`). A stub `R.java` generated from `res/` stood in for `aapt2`'s. Exit code 0.
- **Unit tests**: `bootstrap/tests/run_tests.sh`, 54 checks on `LangUtil`, `ScrubRates`, `TimeFmt`, `SubtitleRanker`
  and `SubtitleDecoder`. This caught one real bug (French showed as "FRA"), now fixed.
- **Icons**: the 13 path definitions were rendered as SVG in Chromium and checked by eye. The XML parses.
- **Source review**: control flow was walked through against NuvioTV's mpv code for property names and `sub-add`.

**Not** done: running `build.sh` (needs the author's SDK, JDK and keystore paths), `aapt2` compilation of the
vector drawables, `d8`, or any run on a TV or emulator. The UI layout, focus behaviour and mpv calls are unproven at runtime.

## Please check on a device

1. **`vo=gpu` stability** on the Mali box (the reason `mediacodec_embed` was used). Play any file. If it faults, toggle *GPU renderer* off.
2. **Subtitles render at all.** Libass needs a font: `sub-font=Roboto` and `sub-fonts-dir=/system/fonts` are set, but this is untested. If text is missing or boxes appear, the font is the first suspect.
3. **`sub-add … cached` selects the new track.** Behaviour is taken from NuvioTV's usage. If a track is added but not shown, change `"cached"` to `"select"` in `PlayerActivity.addOnlineSubtitle`.
4. **libmpv version**: property names such as `sub-outline-size` and `sub-filter-sdh` need a reasonably recent libmpv. Each `setProperty*` is individually try/caught, so an unknown one is skipped, not fatal, but the matching control would do nothing.
5. **`hwdec=mediacodec,mediacodec-copy`** (priority list) needs a libmpv that accepts lists.
6. **Focus feel**: D-pad order (back ↔ seek ↔ buttons), panel focus lock, and the 4.5 s auto-hide. Also that `FLAG_FULLSCREEN` plus the panel looks right on the target resolution.
7. **Seeking on torrent streams**: seeking relies on the local HTTP server honouring range requests.

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
