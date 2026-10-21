# Cahaya v0.1 bootstrap

Zero-dependency Android TV build of Cahaya, produced with the manual
aapt2 → javac → d8 → zipalign → apksigner pipeline.

**Why this exists:** Gradle daemon IPC is broken in this sandbox, so the
canonical Gradle/Kotlin project in `../app/` cannot be built here yet.
This bootstrap is plain Java using only framework APIs
(`HttpURLConnection`, `org.json`, programmatic views — no Leanback, no
Media3, no OkHttp) and proves the full install path on the TV box.

**What v0.1 does:**
- Leanback launcher entry ("Cahaya" in the TV app row)
- D-pad navigable home screen: "Popular Movies" + "Popular Series" rows
  with real posters, fetched live from the Cinemeta Stremio addon
  (auto-installed on first run)
- Card click → toast ("details in v0.2")

**Build:** `./build.sh` → `Cahaya-v0.1.apk` (signed with
`../keystore/cahaya.jks` — keep that file safe, reuse for updates).

When Gradle builds work again (e.g. on the forge machine), the real
build is `../app` — this bootstrap then retires.
