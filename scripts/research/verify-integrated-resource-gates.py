#!/usr/bin/env python3
"""Recompute the integrated resource timing gates from raw device files."""

import hashlib
import json
import math
import pathlib
import statistics
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: verify-integrated-resource-gates.py RAW_DIR EXACT_APK")
root = pathlib.Path(sys.argv[1])
apk = pathlib.Path(sys.argv[2])
summary = json.loads((root / "summary.json").read_text())
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
if summary["apk"] != {"bytes": apk.stat().st_size, "sha256": digest}:
    raise SystemExit("resource summary APK identity mismatch")

def stats(values):
    ordered = sorted(values)
    if len(values) != 10:
        raise SystemExit(f"expected 10 timing samples, got {len(values)}")
    return {"samples": values, "median": statistics.median(ordered), "p95": ordered[math.ceil(.95 * len(ordered)) - 1]}

webview = []
for engine in ("rime", "hunspell", "mozc"):
    first_docs = [json.loads((root / "timing" / f"{engine}-first-{run}.json").read_text()) for run in range(1, 11)]
    first = [document["measurement"]["elapsedMs"] for document in first_docs]
    warm_doc = json.loads((root / "timing" / f"{engine}-warm-switch.json").read_text())
    reopen_doc = json.loads((root / "timing" / f"{engine}-reopen.json").read_text())
    warm_docs = warm_doc["measurement"]["samples"]
    warm = [item["elapsedMs"] for item in warm_docs]
    reopen_docs = reopen_doc["measurement"]["samples"]
    reopen = [item["elapsedMs"] for item in reopen_docs]
    recomputed = {"firstReadyMs": stats(first), "warmSwitchMs": stats(warm), "reopenMs": stats(reopen)}
    recomputed["firstInputableMs"] = stats([document["measurement"]["inputableMs"] for document in first_docs])
    recomputed["firstEngineReadyMs"] = stats([document["measurement"]["engineReadyMs"] for document in first_docs])
    recomputed["warmInputableMs"] = stats([item["inputableMs"] for item in warm_docs])
    recomputed["warmEngineReadyMs"] = stats([item["engineReadyMs"] for item in warm_docs])
    recomputed["reopenInputableMs"] = stats([item["inputableMs"] for item in reopen_docs])
    recomputed["reopenEngineReadyMs"] = stats([item["engineReadyMs"] for item in reopen_docs])
    if recomputed != summary["timing"][engine]:
        raise SystemExit(f"timing summary mismatch: {engine}")
    # The gate bounds time-to-inputable (Direct fallback accepts keys immediately);
    # engine readiness is bounded separately because upstream Hunspell must
    # fully parse its dictionary before its first candidate.
    if recomputed["firstInputableMs"]["p95"] > 200:
        raise SystemExit(f"first inputable gate failed: {engine}")
    if recomputed["firstEngineReadyMs"]["p95"] > 3000:
        raise SystemExit(f"first engine-ready gate failed: {engine}")
    if recomputed["warmInputableMs"]["p95"] > 200:
        raise SystemExit(f"warm inputable gate failed: {engine}")
    if recomputed["warmEngineReadyMs"]["p95"] > 1000:
        raise SystemExit(f"warm engine-ready gate failed: {engine}")
    if recomputed["reopenInputableMs"]["p95"] > 300:
        raise SystemExit(f"reopen inputable gate failed: {engine}")
    if recomputed["reopenEngineReadyMs"]["p95"] > 1000:
        raise SystemExit(f"reopen engine-ready gate failed: {engine}")
    if engine == "rime": webview = [document["webViewReadyMs"] for document in first_docs]
if stats(webview) != summary["webViewReadyMs"]:
    raise SystemExit("WebView-ready summary mismatch")

pss = {}
for path in sorted((root / "pss").glob("*.txt")):
    values = [int(value) for value in path.read_text().split()]
    if len(values) != 5:
        raise SystemExit(f"expected 5 PSS samples: {path}")
    pss[path.stem] = values
if pss != summary["pssKiB"]:
    raise SystemExit("PSS summary mismatch")
baseline = statistics.median(pss["asr-loaded"])
for point in ("asr-plus-rime", "asr-plus-hunspell", "asr-plus-mozc", "ten-switch-idle"):
    delta = statistics.median(pss[point]) - baseline
    if delta > 80 * 1024:
        raise SystemExit(f"resident PSS delta gate failed: {point}={delta} KiB")

clean = json.loads((root / "clean-provision.json").read_text())
if not clean["provisionedNow"] or clean["directReadyNanos"] > clean["provisionStartedNanos"]:
    raise SystemExit("clean provision/direct-ready state boundary is invalid")
print("integrated dynamic resource gates passed")
