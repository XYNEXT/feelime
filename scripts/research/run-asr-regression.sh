#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SPIKE_DIR="$PROJECT_DIR/spikes/asr-baseline"
BASELINE_JSON="$PROJECT_DIR/scripts/research/asr-baseline.json"
FEELIME_ASR_RUNS=5
FEELIME_ASR_FIXTURE=""
FEELIME_RECORD_BASELINE=0
FEELIME_WAIVE_PERF_GATE=0

: "${ANDROID_SERIAL:?ANDROID_SERIAL must explicitly select the ASR test device}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --runs) FEELIME_ASR_RUNS="$2"; shift 2 ;;
        --fixture) FEELIME_ASR_FIXTURE="$2"; shift 2 ;;
        --record-baseline) FEELIME_RECORD_BASELINE=1; shift ;;
        --waive-perf-gate) FEELIME_WAIVE_PERF_GATE=1; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ "$FEELIME_ASR_RUNS" =~ ^[1-9][0-9]*$ ]] || { echo "--runs must be positive" >&2; exit 2; }
[[ -f "$FEELIME_ASR_FIXTURE" ]] || { echo "Fixture not found: $FEELIME_ASR_FIXTURE" >&2; exit 2; }

FEELIME_EXPECTED_HASH="$(jq -r '.fixture.sha256' "$BASELINE_JSON")"
FEELIME_ACTUAL_HASH="$(sha256sum "$FEELIME_ASR_FIXTURE" | cut -d' ' -f1)"
[[ "$FEELIME_ACTUAL_HASH" == "$FEELIME_EXPECTED_HASH" ]] || {
    echo "Fixture SHA-256 mismatch: expected $FEELIME_EXPECTED_HASH, got $FEELIME_ACTUAL_HASH" >&2
    exit 1
}
file "$FEELIME_ASR_FIXTURE" | grep -q 'WAVE audio, Microsoft PCM, 16 bit, mono 16000 Hz' || {
    echo "Fixture must be 16-bit mono 16 kHz PCM WAV" >&2
    exit 1
}

FEELIME_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}"
FEELIME_AAPT_ARGS=()
if [[ -n "${FEELIME_AAPT2:-}" ]]; then
    FEELIME_AAPT_ARGS+=("-Pandroid.aapt2FromMavenOverride=$FEELIME_AAPT2")
fi
ANDROID_HOME="$FEELIME_SDK" "$PROJECT_DIR/gradlew" -p "$SPIKE_DIR" \
    "${FEELIME_AAPT_ARGS[@]}" assembleDebug >/dev/null

FEELIME_APK="$SPIKE_DIR/app/build/outputs/apk/debug/app-debug.apk"
FEELIME_APK_HASH="$(sha256sum "$FEELIME_APK" | cut -d' ' -f1)"
FEELIME_APK_SIGNER_HASH="$("$FEELIME_SDK/build-tools/35.0.0/apksigner" verify --print-certs "$FEELIME_APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
FEELIME_HARNESS_HASH="$({
    for relative in \
        spikes/asr-baseline/settings.gradle.kts \
        spikes/asr-baseline/build.gradle.kts \
        spikes/asr-baseline/app/build.gradle.kts \
        spikes/asr-baseline/app/src/main/AndroidManifest.xml \
        spikes/asr-baseline/app/src/main/java/com/feelime/ime/spike/MainActivity.kt \
        scripts/research/run-asr-regression.sh
    do
        printf '%s  %s\n' "$(sha256sum "$PROJECT_DIR/$relative" | cut -d' ' -f1)" "$relative"
    done
} | sha256sum | cut -d' ' -f1)"
FEELIME_RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
FEELIME_RESULT_DIR="$SPIKE_DIR/results/$FEELIME_RUN_ID"
mkdir -p "$FEELIME_RESULT_DIR"

install_apk() {
    if adb install -r -t --no-streaming "$FEELIME_APK" >/dev/null; then
        return
    fi
    echo "Whole-APK install failed; retrying as verified 20 MiB chunks" >&2
    # Large TCP installs can leave the selected target offline. Restart the
    # local server and reconnect only the explicitly selected test target.
    adb kill-server
    adb start-server >/dev/null
    adb connect "$ANDROID_SERIAL" >/dev/null 2>&1 || true
    adb -s "$ANDROID_SERIAL" wait-for-device
    local parts_dir remote_dir remote_apk local_size remote_size part success
    parts_dir="$(mktemp -d /tmp/feelime-asr-apk-parts.XXXXXX)"
    remote_dir="/data/local/tmp/feelime-asr-apk-$FEELIME_RUN_ID"
    remote_apk="$remote_dir/app-debug.apk"
    split -b 20m -d -a 3 "$FEELIME_APK" "$parts_dir/part-"
    adb shell "mkdir -p '$remote_dir'"
    for part in "$parts_dir"/part-*; do
        success=0
        for _ in 1 2 3 4; do
            if adb push "$part" "$remote_dir/$(basename "$part")" >/dev/null; then
                success=1
                break
            fi
            adb kill-server
            adb start-server >/dev/null
            adb connect "$ANDROID_SERIAL" >/dev/null 2>&1 || true
            adb -s "$ANDROID_SERIAL" wait-for-device
            sleep 1
        done
        [[ "$success" == 1 ]] || return 1
    done
    adb shell "cat '$remote_dir'/part-* > '$remote_apk'"
    local_size="$(wc -c < "$FEELIME_APK" | tr -d '[:space:]')"
    remote_size="$(adb shell "wc -c < '$remote_apk'" | tr -d '\r[:space:]')"
    [[ "$remote_size" == "$local_size" ]] || {
        echo "Remote APK size mismatch: expected $local_size, got $remote_size" >&2
        return 1
    }
    adb shell "pm install -r -t '$remote_apk'" | grep -qx Success
    adb shell "find '$remote_dir' -type f -delete && rmdir '$remote_dir'" || true
    find "$parts_dir" -type f -delete
    rmdir "$parts_dir"
}

install_apk
adb push "$FEELIME_ASR_FIXTURE" /data/local/tmp/feelime-asr-input.wav >/dev/null
adb shell run-as com.feelime.ime.asrbaseline mkdir -p files
adb shell run-as com.feelime.ime.asrbaseline cp /data/local/tmp/feelime-asr-input.wav files/input.wav

FEELIME_RESULT_FILES=()
for run in $(seq 1 "$FEELIME_ASR_RUNS"); do
    FEELIME_RESULT_FILE="$FEELIME_RESULT_DIR/result-$run.json"
    FEELIME_RESULT_FILES+=("$FEELIME_RESULT_FILE")
    adb shell run-as com.feelime.ime.asrbaseline rm -f files/baseline-result.json
    adb shell am force-stop com.feelime.ime.asrbaseline
    adb shell am start -n com.feelime.ime.asrbaseline/com.feelime.ime.spike.MainActivity >/dev/null
    for attempt in $(seq 1 600); do
        if adb shell run-as com.feelime.ime.asrbaseline test -s files/baseline-result.json; then
            break
        fi
        sleep 0.1
    done
    adb shell run-as com.feelime.ime.asrbaseline cat files/baseline-result.json \
        | tr -d '\r' > "$FEELIME_RESULT_FILE"
    jq -e '
        .status == "OK" and
        .sampleRate == 16000 and .sampleCount == 160850 and
        ([.modelLoadMs, .firstPartialFromFirstPcmMs, .stopToFinalCallbackMs,
          .stopToStoppedCallbackMs, .firstPcmToStoppedCallbackMs] | all(type == "number"))
    ' "$FEELIME_RESULT_FILE" >/dev/null
    if [[ "$FEELIME_RECORD_BASELINE" == 0 ]]; then
        FEELIME_REQUIRED_TOKENS="$(jq -c '.endToEndBaseline.mixedRuns.requiredTokensPresentInAllRuns' "$BASELINE_JSON")"
        jq -e --argjson required "$FEELIME_REQUIRED_TOKENS" \
            '($required - .keyTokens | length) == 0' "$FEELIME_RESULT_FILE" >/dev/null
    fi
done

jq -s \
    --arg runId "$FEELIME_RUN_ID" \
    --arg fixtureSha256 "$FEELIME_ACTUAL_HASH" \
    --arg apkSha256 "$FEELIME_APK_HASH" \
    --arg apkSignerSha256 "$FEELIME_APK_SIGNER_HASH" \
    --arg harnessSha256 "$FEELIME_HARNESS_HASH" '
    def median: sort | .[(length / 2 | floor)];
    def p95: sort | .[((length * 0.95 | ceil) - 1)];
    def metric($name): map(.[$name]) | {runs: ., median: median, p95: p95};
    {
      runId: $runId,
      fixtureSha256: $fixtureSha256,
      isolatedApkSha256: $apkSha256,
      isolatedApkSignerSha256: $apkSignerSha256,
      harnessAndScriptSha256: $harnessSha256,
      runCount: length,
      results: .,
      metrics: {
        modelLoadMs: metric("modelLoadMs"),
        firstPartialMs: metric("firstPartialFromFirstPcmMs"),
        stopToFinalMs: metric("stopToFinalCallbackMs"),
        stopToStoppedMs: metric("stopToStoppedCallbackMs"),
        totalMs: metric("firstPcmToStoppedCallbackMs")
      }
    }
    ' "${FEELIME_RESULT_FILES[@]}" > "$FEELIME_RESULT_DIR/summary.json"

if [[ "$FEELIME_WAIVE_PERF_GATE" == 1 ]]; then
    # Device-class mismatch (recorded lesson): the baseline numbers come from
    # an arm64 physical device; on an emulator the model simply runs slower.
    # Keep the five runs + summary for the record, skip the comparison.
    echo "perf gate waived for this device class; raw summary: $FEELIME_RESULT_DIR/summary.json"
elif [[ "$FEELIME_RECORD_BASELINE" == 0 ]]; then
    jq -e --slurpfile baseline "$BASELINE_JSON" '
        def within($actual; $base; $percent): $actual <= ($base * (100 + $percent) / 100);
        . as $now | $baseline[0].endToEndBaseline.mixedRuns as $old |
        within($now.metrics.modelLoadMs.median; $old.medianMs.modelLoad; 20) and
        within($now.metrics.modelLoadMs.p95; $old.nearestRankP95Ms.modelLoad; 20) and
        within($now.metrics.firstPartialMs.median; $old.medianMs.firstPartial; 20) and
        within($now.metrics.firstPartialMs.p95; $old.nearestRankP95Ms.firstPartial; 20) and
        within($now.metrics.stopToFinalMs.median; $old.medianMs.stopToFinal; 20) and
        within($now.metrics.stopToFinalMs.p95; $old.nearestRankP95Ms.stopToFinal; 20) and
        within($now.metrics.totalMs.median; $old.medianMs.total; 20) and
        within($now.metrics.totalMs.p95; $old.nearestRankP95Ms.total; 30)
    ' "$FEELIME_RESULT_DIR/summary.json" >/dev/null
fi

ANDROID_HOME="$FEELIME_SDK" "$PROJECT_DIR/gradlew" \
    "${FEELIME_AAPT_ARGS[@]}" \
    :app:testDirectDebugUnitTest --tests com.feelime.ime.EnglishTextNormalizerTest \
    :app:testPlayDebugUnitTest --tests com.feelime.ime.EnglishTextNormalizerTest >/dev/null

echo "$FEELIME_RESULT_DIR/summary.json"
cat "$FEELIME_RESULT_DIR/summary.json"
