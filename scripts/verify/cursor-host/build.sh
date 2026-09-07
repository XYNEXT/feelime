#!/usr/bin/env bash
set -euo pipefail

# Build the standalone ordinary-EditText cursor probe without Gradle. The
# APK and all generated files stay outside the repository.
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT=${FEELIME_CURSOR_HOST_OUT:-"${TMPDIR:-/tmp}/feelime-0175/cursor-host"}
ANDROID_SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$ANDROID_SDK" || ! -d "$ANDROID_SDK" ]]; then
    echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to an Android SDK" >&2
    exit 2
fi

pick_latest() {
    local pattern="$1"
    find "$ANDROID_SDK"/$pattern -maxdepth 0 -type d 2>/dev/null | sort -V | tail -n 1
}

PLATFORM=${FEELIME_CURSOR_HOST_PLATFORM:-}
if [[ -z "$PLATFORM" ]]; then
    PLATFORM=$(pick_latest 'platforms/android-*')
fi
BUILD_TOOLS=${FEELIME_CURSOR_HOST_BUILD_TOOLS:-}
if [[ -z "$BUILD_TOOLS" ]]; then
    BUILD_TOOLS=$(pick_latest 'build-tools/*')
fi
if [[ -z "$PLATFORM" || ! -f "$PLATFORM/android.jar" || -z "$BUILD_TOOLS" ]]; then
    echo "Android platform/build-tools not found" >&2
    exit 2
fi

AAPT2=${AAPT2:-$BUILD_TOOLS/aapt2}
D8=${D8:-$BUILD_TOOLS/d8}
APKSIGNER=${APKSIGNER:-$BUILD_TOOLS/apksigner}
ZIPALIGN=${ZIPALIGN:-$BUILD_TOOLS/zipalign}
JAVAC=${JAVAC:-javac}
JAR=${JAR:-jar}
KEYTOOL=${KEYTOOL:-keytool}
for tool in "$AAPT2" "$D8" "$APKSIGNER" "$JAVAC" "$JAR" "$KEYTOOL"; do
    if [[ "$tool" == */* && ! -x "$tool" ]]; then
        echo "missing executable: $tool" >&2
        exit 2
    fi
done

rm -rf "$OUT"
mkdir -p "$OUT" "$OUT/generated" "$OUT/classes" "$OUT/dex" "$OUT/res-compiled"

"$AAPT2" compile --dir "$SCRIPT_DIR/res" -o "$OUT/resources.zip"
"$AAPT2" link \
    -o "$OUT/linked.apk" \
    --manifest "$SCRIPT_DIR/AndroidManifest.xml" \
    -I "$PLATFORM/android.jar" \
    --java "$OUT/generated" \
    --min-sdk-version 26 \
    --target-sdk-version 35 \
    "$OUT/resources.zip"

find "$SCRIPT_DIR/src" "$OUT/generated" -name '*.java' -print > "$OUT/sources.list"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 \
    -classpath "$PLATFORM/android.jar" \
    -d "$OUT/classes" \
    @"$OUT/sources.list"

"$JAR" --create --file "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --lib "$PLATFORM/android.jar" --min-api 26 \
    --output "$OUT/dex" "$OUT/classes.jar"

cp "$OUT/linked.apk" "$OUT/unsigned.apk"
if command -v zip >/dev/null 2>&1; then
    (cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
else
    echo "zip command is required to assemble the standalone APK" >&2
    exit 2
fi

KEYSTORE=${FEELIME_CURSOR_HOST_KEYSTORE:-}
STOREPASS=${FEELIME_CURSOR_HOST_STOREPASS:-android}
KEYALIAS=${FEELIME_CURSOR_HOST_KEYALIAS:-androiddebugkey}
KEYPASS=${FEELIME_CURSOR_HOST_KEYPASS:-android}
if [[ -z "$KEYSTORE" ]]; then
    KEYSTORE=${HOME}/.android/debug.keystore
fi
if [[ ! -f "$KEYSTORE" ]]; then
    KEYSTORE="$OUT/debug.keystore"
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KEYSTORE" -storepass "$STOREPASS" \
        -alias "$KEYALIAS" -keypass "$KEYPASS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=Android Debug,O=Android,C=US' >/dev/null 2>&1
fi

SIGN_INPUT="$OUT/unsigned.apk"
if [[ -x "$ZIPALIGN" ]]; then
    "$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
    SIGN_INPUT="$OUT/aligned.apk"
fi
"$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-pass "pass:$STOREPASS" \
    --ks-key-alias "$KEYALIAS" --key-pass "pass:$KEYPASS" \
    --out "$OUT/cursor-host-debug.apk" "$SIGN_INPUT"
"$APKSIGNER" verify --verbose "$OUT/cursor-host-debug.apk" >/dev/null

APK="$OUT/cursor-host-debug.apk"
APK_SHA=$(sha256sum "$APK" | awk '{print $1}')
APK_BYTES=$(stat -c '%s' "$APK")
cat > "$OUT/BUILD_INFO.txt" <<EOF
package=org.example.feelimecursorprobe
activity=org.example.feelimecursorprobe/.MainActivity
apk=$APK
bytes=$APK_BYTES
sha256=$APK_SHA
platform=$(basename "$PLATFORM")
buildTools=$(basename "$BUILD_TOOLS")
uiIds=probe_title,probe_instructions,probe_editor,probe_text,probe_selection,probe_input_type,probe_counters,probe_reset,probe_middle,probe_show_ime
EOF

echo "APK: $APK"
echo "SHA-256: $APK_SHA"
echo "UI IDs: probe_editor probe_text probe_selection probe_counters probe_reset probe_middle probe_show_ime"
