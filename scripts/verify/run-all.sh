#!/usr/bin/env bash
# Full verification gate (docs/testing/verification.md). All environment-specific
# values come from required environment variables — no real
# hostnames, serials or paths are stored in the repo:
#   FEELIME_ADB_SERIAL  adb serial of the connected test device
#   FEELIME_VERIFY_APK  exact main APK already installed on the test device
#   FEELIME_ASR_FIXTURE frozen 16 kHz WAV used by the ASR gate
#   FEELIME_BUILDER_SSH ssh target of the build host (optional; JVM suites only)
#   FEELIME_AAPT2       real AAPT2 executable (optional local Gradle override)
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

: "${FEELIME_ADB_SERIAL:?FEELIME_ADB_SERIAL is required (adb serial of the test device)}"
: "${FEELIME_VERIFY_APK:?FEELIME_VERIFY_APK is required (exact installed APK)}"
: "${FEELIME_ASR_FIXTURE:?FEELIME_ASR_FIXTURE is required (frozen ASR WAV)}"

[[ -f "$FEELIME_VERIFY_APK" ]] || { echo "verification APK is missing" >&2; exit 2; }
local_apk_sha=$(sha256sum "$FEELIME_VERIFY_APK" | awk '{print $1}')
device_apk_path=$(adb -s "$FEELIME_ADB_SERIAL" shell pm path com.feelime.ime | sed -n 's/^package://p' | tr -d '\r' | head -1)
[[ -n "$device_apk_path" ]] || { echo "Feelime is not installed on the target" >&2; exit 2; }
device_apk_sha=$(adb -s "$FEELIME_ADB_SERIAL" shell sha256sum "$device_apk_path" | awk '{print $1}')
[[ "$local_apk_sha" == "$device_apk_sha" ]] || {
    echo "installed APK does not match FEELIME_VERIFY_APK" >&2
    exit 2
}
echo "installed APK identity verified: $local_apk_sha"

GRADLE_AAPT_ARGS=()
if [[ -n "${FEELIME_AAPT2:-}" ]]; then
    GRADLE_AAPT_ARGS+=("-Pandroid.aapt2FromMavenOverride=$FEELIME_AAPT2")
fi

echo "== 1/11 CSS static lint (local node) =="
node "$HERE/css_lint.js"
python3 "$HERE/../generate-keyboard-data.py" --check
python3 "$HERE/../generate-phrase-initials.py" --check

echo "== 2/11 mock bridge suite (local node) =="
node "$HERE/mock_bridge_tests.js"
echo "== 2b/11 mock settings suite + 3.20.0 baseline (local node) =="
node "$HERE/mock_settings_tests.js"
bash "$HERE/mock_baseline_tests.sh"

echo "== 3/11 application JVM suite (${FEELIME_BUILDER_SSH:-local gradle}) =="
if [[ -n "${FEELIME_BUILDER_SSH:-}" ]]; then
    BUILDER_DIR="${FEELIME_BUILDER_DIR:-~/code/feelime}"
    ssh "$FEELIME_BUILDER_SSH" "cd $BUILDER_DIR && ANDROID_HOME=\${FEELIME_BUILDER_SDK:-/opt/android-sdk} ./gradlew -q testDebugUnitTest" >/dev/null
    echo "== 4/11 native spike JVM suite ($FEELIME_BUILDER_SSH) =="
    ssh "$FEELIME_BUILDER_SSH" "cd $BUILDER_DIR && ANDROID_HOME=\${FEELIME_BUILDER_SDK:-/opt/android-sdk} ./gradlew -q --settings-file \"\$PWD/spikes/native-engine-smoke/settings.gradle.kts\" --project-dir \"\$PWD/spikes/native-engine-smoke\" testDebugUnitTest" >/dev/null
else
    (cd "$HERE/../.." && ./gradlew -q "${GRADLE_AAPT_ARGS[@]}" testDebugUnitTest)
    echo "== 4/11 native spike JVM suite (local gradle) =="
    (cd "$HERE/../.." && ./gradlew -q "${GRADLE_AAPT_ARGS[@]}" \
        --settings-file "$PWD/spikes/native-engine-smoke/settings.gradle.kts" \
        --project-dir "$PWD/spikes/native-engine-smoke" testDebugUnitTest)
fi
echo "JVM suites green"

echo "== 5/11 base device verification =="
python3 "$HERE/device_verify.py"

echo "== 6/11 physical gesture verification =="
python3 "$HERE/device_gesture_verify.py"

echo "== 7/11 extended language/UI verification =="
python3 "$HERE/device_extended_verify.py"

echo "== 8/11 host editor/password verification =="
python3 "$HERE/device_editor_verify.py"

echo "== 9/11 clipboard/favorites panel verification =="
python3 "$HERE/device_panel_verify.py"

echo "== 9a/11 Regression (caps/flick/ink/variants/settings) =="
python3 "$HERE/device_caps_flick_verify.py"

echo "== 9b/11 Regression (preedit/xterm/settings pages) =="
python3 "$HERE/device_settings_phrases_verify.py"

echo "== 9c/11 Verification (keymap/setup pages) =="
python3 "$HERE/device_keymap_verify.py"

echo "== 9d/11 Verification (bar pool/fullwidth/editor strip/menu) =="
python3 "$HERE/device_candidate_pool_verify.py"

echo "== 9e/11 Verification (control layer/landscape/height bridge) =="
python3 "$HERE/device_control_layer_verify.py"

echo "== 9f/11 Verification (ctrl switch/slot/height card) =="
python3 "$HERE/device_control_switch_verify.py"

echo "== 9g/11 Verification (backspace swipe/candidate delete/badge) =="
python3 "$HERE/device_backspace_delete_verify.py"

echo "== 9h/11 Verification (any-candidate delete/meta wire/landscape/asr settings) =="
python3 "$HERE/device_candidate_delete_verify.py"

echo "== 9i/11 Verification (Fn keys/combo band/custom JSON/toolbar/voice) =="
python3 "$HERE/device_fn_custom_verify.py"

echo "== 9j/11 Verification (height/editor/settings/languages) =="
python3 "$HERE/device_height_card_verify.py"

echo "== 9k/11 Verification (phrase codes/French editing/UI language/cursor) =="
python3 "$HERE/device_phrase_codes_verify.py"

echo "== 9l/11 Verification (editor restrictions/pinyin/touch/cursor/reset) =="
python3 "$HERE/device_editor_modes_verify.py"

echo "== 9m/11 Verification (stable parses/quick pair/Fn/control height) =="
python3 "$HERE/device_replay_geometry_verify.py"

echo "== 10/11 height/resource verification =="
python3 "$HERE/device_resource_verify.py" --apk "$FEELIME_VERIFY_APK"

echo "== 11/11 ASR five-run regression gate =="
# The performance gate compares against an arm64 physical-device baseline
# (scripts/research/asr-baseline.json); the x86_64 emulator runs the model
# several times slower (recorded lesson: the gate is not reproducible across
# device classes), so on an AVD this step records the five raw runs but is
# waived - the authoritative verdict comes from the physical-device run.
if [[ "$(adb -s "$FEELIME_ADB_SERIAL" shell getprop ro.kernel.qemu | tr -d '[:space:]')" == "1" ]]; then
    echo "AVD detected: running the ASR suite WITHOUT the perf gate (baseline is physical-device)."
    ANDROID_SERIAL="$FEELIME_ADB_SERIAL" bash "$HERE/../research/run-asr-regression.sh" \
        --runs 5 --fixture "$FEELIME_ASR_FIXTURE" --waive-perf-gate
else
    ANDROID_SERIAL="$FEELIME_ADB_SERIAL" bash "$HERE/../research/run-asr-regression.sh" \
        --runs 5 --fixture "$FEELIME_ASR_FIXTURE"
fi

# ---- 专项套件（不在全量序列里，按需单独跑；依赖额外宿主/探针 APK 或音频）----
# device_cursor_host_verify.py   光标滑动宿主探针（需安装 cursor-host APK）
# device_models_verify.py        模型导入/下载面（需 models 探针 APK）
# device_punctuation_verify.py   标点探针（punctuation-probe 宿主，见其 build.sh）
# device_settings_entry_verify.py 系统入口/设置专项（依赖 device_model_import_verify）
# device_voice_stop_verify.py    生产语音停/取消专项（真实编辑器+触摸）
