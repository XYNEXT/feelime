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

# Device suites flake on transient emulator/host state (a11y dumps, system
# ANR dialogs, screenshot hiccups, cross-suite panel residue) - observed as
# whole-suite failures that pass on an immediate standalone rerun. Re-run a
# failed suite whole, at most FEELIME_GATE_RETRIES extra times (default 2).
# A dead emulator must fail loudly instead of burning retries, and every
# retry that turned a step green is reported in the summary so a green gate
# stays honest.
DEVICE_SUITE_RETRIES=${FEELIME_GATE_RETRIES:-2}
RETRIED_STEPS=()

# A freshly (re)booted emulator can sit behind a SystemUI ANR dialog, which
# blocks the fixtures' editor focus ("test field not found" in every suite).
# Dismiss it deterministically instead of burning suite retries on it.
settle_device() {
    local i dump btn bounds
    for i in $(seq 1 60); do
        [[ "$(adb -s "$FEELIME_ADB_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '[:space:]')" == "1" ]] && break
        sleep 5
    done
    adb -s "$FEELIME_ADB_SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    for i in 1 2 3 4 5; do
        dump=$(adb -s "$FEELIME_ADB_SERIAL" exec-out uiautomator dump /dev/tty 2>/dev/null || true)
        btn=$(grep -oE '<node [^>]*text="(Wait|等待)"[^>]*/>' <<<"$dump" | head -1 || true)
        [[ -z "$btn" ]] && return 0
        bounds=$(sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' <<<"$btn")
        [[ -z "$bounds" ]] && return 0
        read -r x1 y1 x2 y2 <<<"$bounds" || true
        echo "settle: dismissing an ANR dialog (tap Wait)"
        adb -s "$FEELIME_ADB_SERIAL" shell input tap $((( x1 + x2 ) / 2)) $((( y1 + y2 ) / 2))
        sleep 6
    done
}

run_suite() {
    local label="$1"
    shift
    local attempt
    for attempt in 0 $(seq 1 "$DEVICE_SUITE_RETRIES"); do
        if [[ "$attempt" != "0" ]]; then
            if ! adb -s "$FEELIME_ADB_SERIAL" get-state >/dev/null 2>&1; then
                echo "RETRY-ABORT [$label]: device $FEELIME_ADB_SERIAL is gone (emulator died?); restart it and rerun the gate" >&2
                return 1
            fi
            echo "RETRY $attempt/$DEVICE_SUITE_RETRIES [$label]: previous attempt failed - rerunning"
        fi
        settle_device
        if "$@"; then
            if [[ "$attempt" != "0" ]]; then
                RETRIED_STEPS+=("$label (passed on retry $attempt)")
            fi
            return 0
        fi
        sleep 5
    done
    echo "STILL FAILING after $DEVICE_SUITE_RETRIES retries [$label]" >&2
    return 1
}

print_retry_summary() {
    if [[ "${#RETRIED_STEPS[@]}" -gt 0 ]]; then
        echo "gate is green, but ${#RETRIED_STEPS[@]} step(s) only passed on retry:"
        printf '  - %s\n' "${RETRIED_STEPS[@]}"
    fi
}

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
run_suite "5/11 base" python3 "$HERE/device_verify.py"

echo "== 6/11 physical gesture verification =="
run_suite "6/11 gesture" python3 "$HERE/device_gesture_verify.py"

echo "== 7/11 extended language/UI verification =="
run_suite "7/11 extended" python3 "$HERE/device_extended_verify.py"

echo "== 8/11 host editor/password verification =="
run_suite "8/11 editor" python3 "$HERE/device_editor_verify.py"

echo "== 9/11 clipboard/favorites panel verification =="
run_suite "9/11 panel" python3 "$HERE/device_panel_verify.py"

echo "== 9a/11 Regression (caps/flick/ink/variants/settings) =="
run_suite "9a caps-flick" python3 "$HERE/device_caps_flick_verify.py"

echo "== 9b/11 Regression (preedit/xterm/settings pages) =="
run_suite "9b settings-phrases" python3 "$HERE/device_settings_phrases_verify.py"

echo "== 9c/11 Verification (keymap/setup pages) =="
run_suite "9c keymap" python3 "$HERE/device_keymap_verify.py"

echo "== 9d/11 Verification (bar pool/fullwidth/editor strip/menu) =="
run_suite "9d pool" python3 "$HERE/device_candidate_pool_verify.py"

echo "== 9e/11 Verification (control layer/landscape/height bridge) =="
run_suite "9e control-layer" python3 "$HERE/device_control_layer_verify.py"

echo "== 9f/11 Verification (ctrl switch/slot/height card) =="
run_suite "9f ctrl-switch" python3 "$HERE/device_control_switch_verify.py"

echo "== 9g/11 Verification (backspace swipe/candidate delete/badge) =="
run_suite "9g backspace" python3 "$HERE/device_backspace_delete_verify.py"

echo "== 9h/11 Verification (any-candidate delete/meta wire/landscape/asr settings) =="
run_suite "9h delete" python3 "$HERE/device_candidate_delete_verify.py"

echo "== 9i/11 Verification (Fn keys/combo band/custom JSON/toolbar/voice) =="
run_suite "9i fn-voice" python3 "$HERE/device_fn_custom_verify.py"

echo "== 9j/11 Verification (height/editor/settings/languages) =="
run_suite "9j height-card" python3 "$HERE/device_height_card_verify.py"

echo "== 9k/11 Verification (phrase codes/French editing/UI language/cursor) =="
run_suite "9k phrase-codes" python3 "$HERE/device_phrase_codes_verify.py"

echo "== 9l/11 Verification (editor restrictions/pinyin/touch/cursor/reset) =="
run_suite "9l editor-modes" python3 "$HERE/device_editor_modes_verify.py"

echo "== 9m/11 Verification (stable parses/quick pair/Fn/control height) =="
run_suite "9m replay-geometry" python3 "$HERE/device_replay_geometry_verify.py"

echo "== 10/11 height/resource verification =="
run_suite "10/11 resource" python3 "$HERE/device_resource_verify.py" --apk "$FEELIME_VERIFY_APK"

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

print_retry_summary

# ---- 专项套件（不在全量序列里，按需单独跑；依赖额外宿主/探针 APK 或音频）----
# device_cursor_host_verify.py   光标滑动宿主探针（需安装 cursor-host APK）
# device_models_verify.py        模型导入/下载面（需 models 探针 APK）
# device_punctuation_verify.py   标点探针（punctuation-probe 宿主，见其 build.sh）
# device_settings_entry_verify.py 系统入口/设置专项（依赖 device_model_import_verify）
# device_voice_stop_verify.py    生产语音停/取消专项（真实编辑器+触摸）
