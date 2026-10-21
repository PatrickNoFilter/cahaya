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
APK="$ROOT/Cahaya-v0.2.apk"

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
    --version-code 2 --version-name "0.2.0" \
    --java "$B/gen" \
    "$B/flats.zip"

echo "== javac =="
find "$ROOT/src" "$B/gen" -name '*.java' > "$B/sources.txt"
echo "  $(wc -l < "$B/sources.txt") source files"
"$JDK/javac" --release 17 -encoding UTF-8 -nowarn -cp "$AJAR" -d "$B/classes" @"$B/sources.txt"

echo "== d8 =="
"$BT/d8" --lib "$AJAR" --min-api 26 --output "$B/dex" \
    $(find "$B/classes" -name '*.class')

echo "== add classes.dex =="
python3 - "$B/base.apk" "$B/dex/classes.dex" <<'EOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("  dex added")
EOF

echo "== zipalign =="
"$BT/zipalign" -f 4 "$B/base.apk" "$APK"

echo "== apksigner =="
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KSPASS" \
    --key-pass "pass:$KSPASS" "$APK"
"$BT/apksigner" verify --print-certs "$APK" | head -3

echo "BUILT: $APK"
ls -la "$APK"
