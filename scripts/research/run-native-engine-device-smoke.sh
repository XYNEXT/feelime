#!/usr/bin/env bash
set -euo pipefail

serial="${1:?usage: $0 ADB_SERIAL EXACT_APK PUBLIC_LABEL NEW_OUTPUT_DIR}"
apk="${2:?missing APK}"
label="${3:?missing public environment label}"
output="${4:?missing new output directory}"
package=com.feelime.ime.nativeengine
component="$package/.MainActivity"
[[ -f "$apk" ]] || { echo "missing APK: $apk" >&2; exit 2; }
[[ "$label" =~ ^[a-zA-Z0-9._-]+$ ]] || { echo 'unsafe public label' >&2; exit 2; }
[[ ! -e "$output" ]] || { echo "output exists: $output" >&2; exit 2; }
mkdir -p "$output"

sha256sum "$apk" > "$output/apk.sha256"
stat -c '%s' "$apk" > "$output/apk.bytes"
# The isolated test device is reached over a network adb link that can drop
# mid-transfer; retry the transport without changing what is measured.
install_ok=0
for attempt in 1 2 3 4 5; do
  if adb -s "$serial" install --no-streaming -r "$apk" > "$output/install.txt" 2>&1; then
    install_ok=1
    break
  fi
  sleep 5
done
[[ "$install_ok" == 1 ]] || { echo 'apk install failed' >&2; exit 1; }
{
  echo "publicLabel=$label"
  echo "model=$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
  echo "androidRelease=$(adb -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
  echo "api=$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abiList=$(adb -s "$serial" shell getprop ro.product.cpu.abilist | tr -d '\r')"
  echo "fingerprint=$(adb -s "$serial" shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "pageSizeBytes=$(adb -s "$serial" shell getconf PAGE_SIZE | tr -d '\r')"
} > "$output/environment.txt"

# This package is an isolated native-smoke harness. Clearing only its files directory is
# the clean-run boundary; no production package or user data is touched.
adb -s "$serial" shell run-as "$package" rm -rf files
adb -s "$serial" shell run-as "$package" mkdir files
adb -s "$serial" shell run-as "$package" du -ak files > "$output/du-before.txt"
adb -s "$serial" shell am start -W -n "$component" > "$output/am-start.txt"
for _ in $(seq 1 600); do
  if adb -s "$serial" shell run-as "$package" test -f files/native-engine-smoke.json; then
    adb -s "$serial" shell run-as "$package" cat files/native-engine-smoke.json > "$output/result.json"
    jq -e '.status == "passed"' "$output/result.json" >/dev/null
    adb -s "$serial" shell run-as "$package" du -ak files > "$output/du-after.txt"
    echo "native engine device smoke passed: $label"
    exit 0
  fi
  sleep 0.5
done
echo "native engine smoke timed out: $label" >&2
exit 1
