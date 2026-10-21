#!/bin/bash
# Cahaya v0.1 bootstrap build — zero-dependency manual pipeline.
#
# Why this exists: Gradle daemon IPC is broken in this sandbox, so the
# canonical Gradle/Kotlin project (../app) can't build here yet. This
# bootstrap is plain Java + framework APIs only (HttpURLConnection,
# org.json, programmatic views) and builds with aapt2/javac/d8.
# When Gradle works again (e.g. on forge), build ../app instead.
#
# Keystore password lives here only (same pattern as economic-health).
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
B="$ROOT/build"
BT=/home/hatch/workspace/android-sdk/build-tools/34.0.0
AJAR=/home/hatch/workspace/android-sdk/platforms/android-34/android.jar
JDK=/home/hatch/workspace/jdk/jdk-17.0.20.1+1/bin
export PATH="$JDK:$PATH"   # javac, keytool, and java (needed by d8)
KS=/home/hatch/workspace/cahaya/keystore/cahaya.jks
KSPASS="cahaya01"
APK="$ROOT/Cahaya-v0.8.apk"
LTJAR="$ROOT/libs/lt4j.jar"
LTSO64="$ROOT/jniLibs/arm64-v8a/libtorrent4j.so"
LTSO32="$ROOT/jniLibs/armeabi-v7a/libtorrent4j.so"

if [ ! -f "$KS" ]; then
    echo "keystore missing: $KS (generate it first, keep it safe)"
    exit 1
fi

rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex" "$B/flats"

echo "== aapt2 compile =="
$BT/aapt2 compile --dir "$ROOT/res" -o "$B/flats.zip"

echo "== aapt2 link =="
$BT/aapt2 link -o "$B/base.apk" -I "$AJAR" \
    --manifest "$ROOT/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code 9 --version-name "0.8.0" \
    --java "$B/gen" \
    "$B/flats.zip"

echo "== javac =="
find "$ROOT/src" "$B/gen" -name '*.java' > "$B/sources.txt"
echo "  $(wc -l < "$B/sources.txt") source files"
"$JDK/javac" --release 17 -encoding UTF-8 -nowarn -cp "$AJAR:$LTJAR" -d "$B/classes" @"$B/sources.txt"

echo "== d8 =="
"$BT/d8" --lib "$AJAR" --min-api 26 --output "$B/dex" \
    $(find "$B/classes" -name '*.class') "$LTJAR"
ls "$B/dex"

echo "== add dex + native libs =="
python3 - "$B/base.apk" "$B/dex" "$LTSO64" "$LTSO32" <<'EOF'
import sys, zipfile, os
apk, dexdir, so64, so32 = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    for f in sorted(os.listdir(dexdir)):
        if f.endswith('.dex'):
            z.write(os.path.join(dexdir, f), f)
            print("  dex added:", f)
    # native libs must be stored uncompressed for the loader
    z.write(so64, 'lib/arm64-v8a/libtorrent4j.so', compress_type=zipfile.ZIP_STORED)
    print("  so added: lib/arm64-v8a/libtorrent4j.so")
    z.write(so32, 'lib/armeabi-v7a/libtorrent4j.so', compress_type=zipfile.ZIP_STORED)
    print("  so added: lib/armeabi-v7a/libtorrent4j.so")
EOF

echo "== zipalign =="
"$BT/zipalign" -f -p 4 "$B/base.apk" "$APK"

echo "== apksigner =="
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KSPASS" \
    --key-pass "pass:$KSPASS" "$APK"
"$BT/apksigner" verify --print-certs "$APK" | head -3

echo "BUILT: $APK"
ls -la "$APK"
