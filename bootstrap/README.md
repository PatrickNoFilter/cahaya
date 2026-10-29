# Cahaya bootstrap — the shipping build

Zero-dependency Android build of Cahaya, produced with the manual
aapt2 → javac → d8 → zipalign → apksigner pipeline. This is the
build that ships as the release APKs (current: phone 1.11.0, TV box
1.9.0) — not a prototype: the Gradle/Kotlin project in `../app/` is
a stub that cannot build in this sandbox (Gradle daemon IPC is
broken there), so this bootstrap is the canonical build.

Plain Java using only framework APIs (`HttpURLConnection`, `org.json`,
programmatic views — no Leanback, no Media3, no OkHttp). One universal
APK per build: arm64-v8a + armeabi-v7a native libs, TV + phone UI
auto-detected at runtime.

**What it ships:** Stremio addon protocol engine (Cinemeta, Torrentio,
MediaFusion, OpenSubtitles, …), own trending rows (Korean, Japanese,
Chinese, Hollywood, Worldwide), libmpv player with self-drawn online
subtitles, in-app torrent streaming, WebView JS scrapers, QR + phone
web addon install, Google device-code sign-in.

**Build:** `./build.sh` → `Cahaya-vX.Y.Z.apk` (signed with
`../keystore/cahaya.jks` — keep that file safe, reuse for updates).
`build.sh` sets the version (APK name, version-code, version-name).

**Install:** phone — tap the APK in Download. Box —
`adb install --no-incremental -r Cahaya-vX.Y.Z.apk` (plain
incremental install reports Success without installing a ~93 MB APK).
