#!/usr/bin/env bash
set -euo pipefail

# Build a tiny platform instrumentation APK. It intentionally packages only
# the Java runner: ModelStore, sherpa JNI, and TranscriptPostProcessor are
# loaded from the installed production APK at instrumentation time.
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT=${FEELIME_PUNCTUATION_PROBE_OUT:-"$HOME/tmp/feelime/punctuation-probe"}
ANDROID_SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$ANDROID_SDK" || ! -d "$ANDROID_SDK" ]]; then
    echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to an Android SDK" >&2
    exit 2
fi

pick_latest() {
    local pattern="$1"
    find "$ANDROID_SDK"/$pattern -maxdepth 0 -type d 2>/dev/null | sort -V | tail -n 1
}

PLATFORM=${FEELIME_PUNCTUATION_PROBE_PLATFORM:-}
if [[ -z "$PLATFORM" ]]; then
    PLATFORM=$(pick_latest 'platforms/android-*')
fi
BUILD_TOOLS=${FEELIME_PUNCTUATION_PROBE_BUILD_TOOLS:-}
if [[ -z "$BUILD_TOOLS" ]]; then
    BUILD_TOOLS=$(pick_latest 'build-tools/*')
fi
if [[ -z "$PLATFORM" || ! -f "$PLATFORM/android.jar" || -z "$BUILD_TOOLS" ]]; then
    echo "Android platform/build-tools not found" >&2
    exit 2
fi

AAPT2=${FEELIME_PUNCTUATION_PROBE_AAPT2:-${FEELIME_AAPT2:-$BUILD_TOOLS/aapt2}}
D8=${FEELIME_PUNCTUATION_PROBE_D8:-$BUILD_TOOLS/d8}
APKSIGNER=${FEELIME_PUNCTUATION_PROBE_APKSIGNER:-$BUILD_TOOLS/apksigner}
ZIPALIGN=${FEELIME_PUNCTUATION_PROBE_ZIPALIGN:-$BUILD_TOOLS/zipalign}
JAVAC=${FEELIME_PUNCTUATION_PROBE_JAVAC:-javac}
JAR=${FEELIME_PUNCTUATION_PROBE_JAR:-jar}
KEYTOOL=${FEELIME_PUNCTUATION_PROBE_KEYTOOL:-keytool}
for tool in "$AAPT2" "$D8" "$APKSIGNER" "$JAVAC" "$JAR" "$KEYTOOL"; do
    if [[ "$tool" == */* && ! -x "$tool" ]]; then
        echo "missing executable: $tool" >&2
        exit 2
    fi
done

KEYSTORE=${FEELIME_PUNCTUATION_PROBE_KEYSTORE:-"$HOME/.android/debug.keystore"}
STOREPASS=${FEELIME_PUNCTUATION_PROBE_STOREPASS:-android}
KEYALIAS=${FEELIME_PUNCTUATION_PROBE_KEYALIAS:-androiddebugkey}
KEYPASS=${FEELIME_PUNCTUATION_PROBE_KEYPASS:-android}
if [[ ! -f "$KEYSTORE" ]]; then
    echo "debug keystore not found: $KEYSTORE" >&2
    echo "The instrumentation APK must use the same debug key as the production APK" >&2
    exit 2
fi

rm -rf "$OUT"
mkdir -p "$OUT" "$OUT/generated" "$OUT/classes" "$OUT/dex"

"$AAPT2" link \
    -o "$OUT/linked.apk" \
    --manifest "$SCRIPT_DIR/AndroidManifest.xml" \
    -I "$PLATFORM/android.jar" \
    --java "$OUT/generated" \
    --min-sdk-version 26 \
    --target-sdk-version 35

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
    echo "zip command is required to assemble the instrumentation APK" >&2
    exit 2
fi

SIGN_INPUT="$OUT/unsigned.apk"
if [[ -x "$ZIPALIGN" ]]; then
    "$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
    SIGN_INPUT="$OUT/aligned.apk"
fi
"$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-pass "pass:$STOREPASS" \
    --ks-key-alias "$KEYALIAS" --key-pass "pass:$KEYPASS" \
    --out "$OUT/punctuation-probe-debug.apk" "$SIGN_INPUT"

APK="$OUT/punctuation-probe-debug.apk"
"$APKSIGNER" verify --verbose "$APK" >/dev/null

certificate_from_keystore() {
    "$KEYTOOL" -J-Duser.language=en -J-Duser.country=US -list -v \
        -keystore "$KEYSTORE" -storepass "$STOREPASS" -alias "$KEYALIAS" 2>/dev/null \
        | awk -F': ' '/SHA256:/{print $2; exit}' | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'
}

certificate_from_apk() {
    "$APKSIGNER" verify --print-certs "$1" 2>/dev/null \
        | awk -F': ' '/certificate SHA-256 digest:/{print $2; exit}' \
        | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'
}

KEY_CERT=$(certificate_from_keystore)
APK_CERT=$(certificate_from_apk "$APK")
if [[ -z "$KEY_CERT" || -z "$APK_CERT" || "$KEY_CERT" != "$APK_CERT" ]]; then
    echo "instrumentation APK signer does not match the debug keystore" >&2
    exit 1
fi

# When the production APK is supplied by the device gate, verify the signer
# before installation too. This catches a production artifact built with a
# different debug key even when both files are otherwise valid APKs.
TARGET_APK=${FEELIME_VERIFY_APK:-}
if [[ -n "$TARGET_APK" ]]; then
    if [[ ! -f "$TARGET_APK" ]]; then
        echo "FEELIME_VERIFY_APK is not a file: $TARGET_APK" >&2
        exit 2
    fi
    TARGET_CERT=$(certificate_from_apk "$TARGET_APK")
    if [[ -z "$TARGET_CERT" || "$TARGET_CERT" != "$KEY_CERT" ]]; then
        echo "production APK signer does not match ~/.android/debug.keystore" >&2
        exit 1
    fi
fi

APK_SHA=$(sha256sum "$APK" | awk '{print $1}')
APK_BYTES=$(stat -c '%s' "$APK")
cat > "$OUT/BUILD_INFO.txt" <<EOF
package=com.feelime.punctuationprobe
targetPackage=com.feelime.ime
instrumentation=com.feelime.punctuationprobe/.PunctuationInstrumentation
apk=$APK
bytes=$APK_BYTES
sha256=$APK_SHA
signerSha256=$KEY_CERT
keystore=$(basename "$KEYSTORE")
platform=$(basename "$PLATFORM")
buildTools=$(basename "$BUILD_TOOLS")
EOF

echo "APK: $APK"
echo "SHA-256: $APK_SHA"
echo "Signer SHA-256: $KEY_CERT"
echo "Instrumentation: com.feelime.punctuationprobe/.PunctuationInstrumentation"
