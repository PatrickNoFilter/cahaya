#!/bin/bash
# Compile + run the pure-Java player logic tests. Needs only a JDK (17+), no Android SDK.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../src/com/patrisius/cahaya"
OUT="$(mktemp -d)"
javac --release 17 -encoding UTF-8 -d "$OUT" \
  "$SRC/addon/SubtitleItem.java" \
  "$SRC/ui/player/LangUtil.java" "$SRC/ui/player/ScrubRates.java" "$SRC/ui/player/TimeFmt.java" \
  "$SRC/ui/player/SubtitleRanker.java" "$SRC/ui/player/SubtitleDecoder.java" \
  "$SRC/ui/player/SubtitleCue.java" "$SRC/ui/player/CueParser.java" "$SRC/ui/player/SubtitleSelection.java" \
  "$HERE/PlayerLogicTest.java"
java -cp "$OUT" PlayerLogicTest
