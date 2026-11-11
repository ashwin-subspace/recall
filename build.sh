#!/usr/bin/env bash
# Gradle-free Android build: aapt2 -> javac -> d8 -> aapt add -> zipalign -> apksigner
set -euo pipefail

has_jdk() {
  [ -n "${1:-}" ] || return 1
  if { [ -x "$1/bin/javac" ] || [ -x "$1/bin/javac.exe" ]; } \
      && { [ -x "$1/bin/jar" ] || [ -x "$1/bin/jar.exe" ]; }; then
    return 0
  fi
  return 1
}

if ! has_jdk "${JAVA_HOME:-}"; then
  for candidate in \
      "C:/Program Files/Android/openjdk/jdk-21.0.8" \
      "C:/Users/hopel/.p2/pool/plugins/org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_21.0.9.v20251105-0741/jre"
  do
    if has_jdk "$candidate"; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if ! has_jdk "${JAVA_HOME:-}"; then
  echo "Set JAVA_HOME to a JDK 21 that contains javac and jar." >&2
  exit 1
fi

if [ -n "${ANDROID_HOME:-}" ]; then
  SDK="$ANDROID_HOME"
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  SDK="$ANDROID_SDK_ROOT"
elif [ -d "C:/Users/hopel/AppData/Local/Android/Sdk" ]; then
  SDK="C:/Users/hopel/AppData/Local/Android/Sdk"
elif [ -d "F:/CLAUDE_PROJECTS/.sdk" ]; then
  SDK="F:/CLAUDE_PROJECTS/.sdk"
else
  echo "Set ANDROID_HOME to an SDK with build-tools 36.0.0 and platforms/android-36." >&2
  exit 1
fi

BT="$SDK/build-tools/36.0.0"
AJ="$SDK/platforms/android-36/android.jar"
if [ ! -f "$AJ" ]; then
  echo "Missing platforms/android-36/android.jar under $SDK" >&2
  exit 1
fi

AAPT2="$BT/aapt2.exe"; [ -f "$AAPT2" ] || AAPT2="$BT/aapt2"
D8="$BT/d8.bat"; [ -f "$D8" ] || D8="$BT/d8"
AAPT="$BT/aapt.exe"; [ -f "$AAPT" ] || AAPT="$BT/aapt"
ZIPALIGN="$BT/zipalign.exe"; [ -f "$ZIPALIGN" ] || ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner.bat"; [ -f "$APKSIGNER" ] || APKSIGNER="$BT/apksigner"
if [ ! -f "$AAPT2" ] || [ ! -f "$D8" ] || [ ! -f "$AAPT" ] || [ ! -f "$ZIPALIGN" ] || [ ! -f "$APKSIGNER" ]; then
  echo "Missing build-tools 36.0.0 under $SDK" >&2
  exit 1
fi

KS="${HOME:-}/.android/debug.keystore"
if [ ! -f "$KS" ] && [ -f "C:/Users/hopel/.android/debug.keystore" ]; then
  KS="C:/Users/hopel/.android/debug.keystore"
fi
if [ ! -f "$KS" ]; then
  echo "No debug keystore at $KS" >&2
  exit 1
fi

OUT=build
APK=Recall.apk

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/7] aapt2 compile"
"$AAPT2" compile --dir res -o "$OUT/res.zip"

echo "[2/7] aapt2 link"
"$AAPT2" link -o "$OUT/base.apk" -I "$AJ" --manifest AndroidManifest.xml -R "$OUT/res.zip" --java "$OUT/gen" --min-sdk-version 24 --target-sdk-version 36 --version-code 1 --version-name 1.0 --auto-add-overlay

echo "[3/7] javac"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if ! "$JAVA_HOME/bin/javac" -nowarn -Xlint:none -source 8 -target 8 -bootclasspath "$AJ" -classpath "$AJ" -d "$OUT/classes" "@$OUT/sources.txt" 2> "$OUT/javac.log"; then
  grep -vE "bootstrap class path|^warning|source value 8|target value 8|deprecat" "$OUT/javac.log" >&2 || true
  echo "BUILD FAILED: javac" >&2
  exit 1
fi

echo "[4/7] jar + d8"
"$JAVA_HOME/bin/jar" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --min-api 24 --output "$OUT/dex" "$OUT/classes.jar"

echo "[5/7] add dex"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
( cd "$OUT" && "$AAPT" add -f base.apk classes.dex >/dev/null )

echo "[6/7] zipalign"
"$ZIPALIGN" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[7/7] sign"
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out "$APK" "$OUT/aligned.apk"

echo "OK -> $APK  ($(stat -c%s "$APK") bytes)"
