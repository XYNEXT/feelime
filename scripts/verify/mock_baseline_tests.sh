#!/bin/sh
# Mock baseline: the shipped 3.20.0 keyboard sources (byte-identical
# to the released APK, see test-fixtures/keyboard-3.20.0/) must keep passing
# the era-gated suite. This gate locks the two things that matter - zero
# failures and no case LOST - while letting the skip count float: every new
# `since:` gate legitimately adds skips on the old fixture, and pinning the
# exact totals turned every batch into a manual recount ritual (2026-09
# review P2-7). A rotting old-form case still shows up as a failure here.
# Historical pins for reference: 126/39 -> 130/0/73 (keyboard 3.30.0 era).
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MIN_PASSED=130

OUT=$(FEELIME_KEYBOARD_SRC="$ROOT/test-fixtures/keyboard-3.20.0" \
    node "$ROOT/scripts/verify/mock_bridge_tests.js") || {
        printf '%s\n' "$OUT" | tail -3
        echo "mock-baseline: suite failed"
        exit 1
    }

SUMMARY=$(printf '%s\n' "$OUT" | grep '^== mock-bridge suite' | tail -1)
echo "mock-baseline: $SUMMARY"

PASSED=$(printf '%s\n' "$SUMMARY" | sed -n 's/.*: \([0-9]*\) passed.*/\1/p')
FAILED=$(printf '%s\n' "$SUMMARY" | sed -n 's/.*, \([0-9]*\) failed,.*/\1/p')

if [ -z "$PASSED" ] || [ -z "$FAILED" ]; then
    echo "mock-baseline: could not parse the suite summary"
    exit 1
fi
if [ "$FAILED" != "0" ]; then
    echo "mock-baseline: era-gated suite has failures on the 3.20.0 fixture"
    exit 1
fi
if [ "$PASSED" -lt "$MIN_PASSED" ]; then
    echo "mock-baseline: passed count dropped below the floor (got $PASSED, floor $MIN_PASSED)"
    exit 1
fi
