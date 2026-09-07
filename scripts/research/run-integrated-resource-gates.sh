#!/usr/bin/env bash
set -euo pipefail

serial="${1:?usage: $0 ADB_SERIAL NEW_OUTPUT_DIR}"
output="${2:?missing new output directory}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
apk="${FEELIME_RESOURCE_APK:-$repo_root/spikes/integrated-resource-smoke/app/build/outputs/apk/debug/app-debug.apk}"
package=com.feelime.ime.resourcebenchmark
component="$package/com.feelime.ime.nativeengine.MainActivity"
[[ -f "$apk" ]] || { echo "build integrated resource smoke APK first" >&2; exit 2; }
[[ ! -e "$output" ]] || { echo "output directory exists: $output" >&2; exit 2; }
mkdir -p "$output/timing" "$output/pss"

# The isolated test device is reached over a network adb link that drops
# individual connections; retry transport-level failures without changing
# what is measured.
retry_adb() {
  local attempt output
  for attempt in 1 2 3 4 5; do
    output="$(adb "$@" 2>/dev/null)" && { printf '%s\n' "$output"; return 0; }
    sleep 2
  done
  return 1
}

if [[ "${FEELIME_PREINSTALLED:-}" != "1" ]]; then
  adb -s "$serial" install --no-streaming -r "$apk" > "$output/install.log"
fi
# Isolated test package only: establish the one clean deployment sample.
retry_adb -s "$serial" shell run-as "$package" rm -rf files >/dev/null

launch() {
  local operation="$1" engine="$2" load_asr="$3" destination="$4"
  # The isolated test device is a live phone whose load can stall a launch
  # indefinitely; a stalled launch is not a sample, so force-stop and retry.
  local attempt
  for attempt in 1 2 3; do
    retry_adb -s "$serial" shell am force-stop "$package" >/dev/null
    retry_adb -s "$serial" shell run-as "$package" rm -f files/native-engine-smoke.json >/dev/null
    retry_adb -s "$serial" shell am start -W -n "$component" --es benchmark "$operation" \
      --es engine "$engine" --ez loadAsr "$load_asr" > "$destination.am-start.txt"
    local _
    for _ in $(seq 1 180); do
      if adb -s "$serial" shell run-as "$package" test -f files/native-engine-smoke.json; then
        if retry_adb -s "$serial" shell run-as "$package" cat files/native-engine-smoke.json > "$destination"; then
          if jq -e --arg op "$operation" --arg engine "$engine" \
            '.status == "passed" and .operation == $op and .engine == $engine' "$destination" >/dev/null; then
            if [[ "$attempt" -gt 1 ]]; then
              echo "launch retry succeeded on attempt $attempt: $operation/$engine" >&2
            fi
            return
          fi
        fi
      fi
      sleep 0.25
    done
    echo "launch attempt $attempt stalled: $operation/$engine" >&2
  done
  echo "benchmark timed out: $operation/$engine" >&2
  exit 1
}

pss_five() {
  local destination="$1" value
  : > "$destination"
  for _ in $(seq 1 5); do
    value=""
    local attempt
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
      value="$(adb -s "$serial" shell dumpsys meminfo "$package" 2>/dev/null | awk '/TOTAL PSS:/ {print $3; found=1} END {exit !found}')" && break
      sleep 3
    done
    [[ -n "$value" ]] || { echo "pss sampling failed: $destination" >&2; exit 1; }
    echo "$value" >> "$destination"
    sleep 1
  done
}

launch direct-idle rime false "$output/clean-provision.json"
pss_five "$output/pss/direct-idle.txt"

for engine in rime hunspell mozc; do
  : > "$output/timing/$engine-first-ready.ndjson"
  for run in $(seq 1 10); do
    launch first-ready "$engine" true "$output/timing/$engine-first-$run.json"
    jq -c . "$output/timing/$engine-first-$run.json" >> "$output/timing/$engine-first-ready.ndjson"
  done
  pss_five "$output/pss/asr-plus-$engine.txt"
  launch warm-switch "$engine" true "$output/timing/$engine-warm-switch.json"
  launch reopen "$engine" true "$output/timing/$engine-reopen.json"
done

launch direct-idle rime true "$output/asr-loaded.json"
pss_five "$output/pss/asr-loaded.txt"
launch switch-stress-idle rime true "$output/ten-switch-idle.json"
pss_five "$output/pss/ten-switch-idle.txt"

python3 - "$output" "$apk" "$serial" <<'PY'
import hashlib, json, pathlib, statistics, sys
root, apk = map(pathlib.Path, sys.argv[1:3])
serial = sys.argv[3]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def summary(values):
    ordered = sorted(values)
    return {"samples": values, "median": statistics.median(ordered), "p95": ordered[9]}
timing = {}
webview = []
def phase(docs, field): return summary([document[field] for document in docs])
for engine in ("rime", "hunspell", "mozc"):
    first_docs = [json.loads(line) for line in (root/"timing"/f"{engine}-first-ready.ndjson").read_text().splitlines()]
    if engine == "rime": webview = [document["webViewReadyMs"] for document in first_docs]
    warm_doc = json.loads((root/"timing"/f"{engine}-warm-switch.json").read_text())
    warm_samples = warm_doc["measurement"]["samples"]
    reopen_doc = json.loads((root/"timing"/f"{engine}-reopen.json").read_text())
    reopen_samples = reopen_doc["measurement"]["samples"]
    timing[engine] = {
        "firstReadyMs": summary([document["measurement"]["elapsedMs"] for document in first_docs]),
        "firstInputableMs": phase([document["measurement"] for document in first_docs], "inputableMs"),
        "firstEngineReadyMs": phase([document["measurement"] for document in first_docs], "engineReadyMs"),
        "warmSwitchMs": summary([x["elapsedMs"] for x in warm_samples]),
        "warmInputableMs": summary([x["inputableMs"] for x in warm_samples]),
        "warmEngineReadyMs": summary([x["engineReadyMs"] for x in warm_samples]),
        "reopenMs": summary([x["elapsedMs"] for x in reopen_samples]),
        "reopenInputableMs": summary([x["inputableMs"] for x in reopen_samples]),
        "reopenEngineReadyMs": summary([x["engineReadyMs"] for x in reopen_samples])}
pss = {path.stem: [int(v) for v in path.read_text().split()] for path in sorted((root/"pss").glob("*.txt"))}
result = {"schemaVersion": 1, "status": "captured", "deviceSerialRedacted": True,
    "apk": {"bytes": apk.stat().st_size, "sha256": sha(apk)}, "webViewReadyMs": summary(webview),
    "timing": timing, "pssKiB": pss,
    "sampling": {"timingRuns": 10, "pssRuns": 5, "clock": "elapsedRealtimeNanos",
        "pssCommand": "dumpsys meminfo <isolated-package>", "pssIntervalSeconds": 1}}
(root/"summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n")
PY

echo "integrated resource gates captured: $output/summary.json"
