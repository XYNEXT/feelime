#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FEELIME_SDK="${ANDROID_HOME:-$HOME/android-sdk}"
GRADLE_ARGS=(assembleDebug)

if [[ "$(uname -m)" == "aarch64" ]]; then
    FEELIME_AAPT2="${FEELIME_AAPT2:-$HOME/.local/aapt2/aapt2}"
    if [[ ! -x "$FEELIME_AAPT2" ]]; then
        echo "arm64 aapt2 launcher not found or not executable: $FEELIME_AAPT2" >&2
        echo "Set FEELIME_AAPT2 to an x86_64-compatible aapt2 binary." >&2
        exit 1
    fi
    GRADLE_ARGS=("-Pandroid.aapt2FromMavenOverride=$FEELIME_AAPT2" "${GRADLE_ARGS[@]}")
fi

cd "$PROJECT_DIR"
ANDROID_HOME="$FEELIME_SDK" ./gradlew "${GRADLE_ARGS[@]}"
