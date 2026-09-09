#!/bin/sh
# Mock baseline: the shipped 3.20.0 keyboard sources (byte-identical
# to the released APK, see test-fixtures/keyboard-3.20.0/) must keep passing
# the era-gated suite. Exact counts are PINNED - any drift (a harness change
# rotting an old-form case, a new skip) fails this gate. See
# docs/testing/verification.md.
# Relative to the previous 126/39 pin, the current suite adds two
# era-compatible voice-card checks (voice-card submit/cancel and gesture
# hints), so both remain passes on the 3.20.0 fixture.  Four newly gated
# current-era checks account for the skip increase observed with the
# fixture: the Fn long-press/click path and three editor-mode checks that
# were absent from the old accounting.  The height-card suite keeps the
# historical custom-keys test and adds the current saved pair test under a
# separate era gate; the latter contributes one more baseline skip.
# Keyboard 3.25.0 adds two rank checks under `since: 3.25.0`, so the
# fixture skips two more.  Keyboards 3.26.0-3.28.1 (open-source initial,
# 1.0.3 nine-pad/quote-en/arrows/shift-combo, 1.0.4 stores mirror) add 18
# current-era checks: three are era-compatible and stay passes on the
# fixture, seven gate at 3.27.0 and eight at 3.28.0.  The resulting
# evidence-backed pin is 131 passed / 0 failed / 61 skipped.
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PINNED='== mock-bridge suite: 131 passed, 0 failed, 61 skipped (era-gated) [keyboard 3.20.0] =='

OUT=$(FEELIME_KEYBOARD_SRC="$ROOT/test-fixtures/keyboard-3.20.0" \
    node "$ROOT/scripts/verify/mock_bridge_tests.js") || {
        printf '%s\n' "$OUT" | tail -3
        echo "mock-baseline: suite failed"
        exit 1
    }

SUMMARY=$(printf '%s\n' "$OUT" | grep '^== mock-bridge suite' | tail -1)
echo "mock-baseline: $SUMMARY"

if [ "$SUMMARY" != "$PINNED" ]; then
    echo "mock-baseline: PINNED counts drifted; expected:"
    echo "  $PINNED"
    exit 1
fi
