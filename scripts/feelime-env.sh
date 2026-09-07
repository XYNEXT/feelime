#!/usr/bin/env bash
# Machine-local Feelime build environment (~/.config/feelime/).
#
# The open-source repo never stores hostnames, SDK paths or signing secrets.
# They live in the per-machine config directory instead (see AGENTS.md,
# "Machine-local build environment"):
#
#   ~/.config/feelime/env.sh    KEY=VALUE lines, '#' comments
#   ~/.config/feelime/models/   shared ASR model tree (setup-assets.sh target)
#   ~/.config/feelime/android/  shared large Android assets (sherpa-onnx AAR)
#
# Loading rules: only FEELIME_* variables that are NOT already set in the
# environment get filled from the file, so an explicit
# `FEELIME_ADB_SERIAL=... bash scripts/verify/run-all.sh` always wins.
# The file is parsed, never executed.
#
# Usage:  . "$(dirname "$0")/feelime-env.sh"   (loads immediately on source)

FEELIME_CONFIG_DIR="${FEELIME_CONFIG_DIR:-$HOME/.config/feelime}"

feelime_load_env() {
    local file="$FEELIME_CONFIG_DIR/env.sh"
    [[ -f "$file" ]] || return 0
    local key value
    while IFS='=' read -r key value; do
        key="${key//[[:space:]]/}"
        [[ "$key" =~ ^FEELIME_[A-Z0-9_]+$ ]] || continue
        [[ -n "${!key:-}" ]] && continue
        value="${value%\"}"
        value="${value#\"}"
        value="${value%\'}"
        value="${value#\'}"
        [[ -n "$value" ]] && export "$key=$value"
    done < <(sed -e 's/#.*$//' "$file")
}

feelime_load_env
