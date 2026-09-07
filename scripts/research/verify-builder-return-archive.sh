#!/usr/bin/env bash
set -euo pipefail

archive="${1:?usage: $0 RETURN_TAR EXPECTED_SHA256 EXPECTED_BYTES STAGING_DIR ACCEPTED_MANIFEST COMBINED_APK}"
expected_sha256="${2:?missing expected SHA-256}"
expected_bytes="${3:?missing expected byte length}"
staging="${4:?missing new staging directory}"
accepted_manifest="${5:?missing accepted third_party manifest}"
combined_apk="${6:?missing exact combined APK}"
[[ ! -e "$staging" ]] || { echo "staging already exists: $staging" >&2; exit 2; }
[[ "$(stat -c '%s' "$archive")" == "$expected_bytes" ]] || { echo 'archive byte length mismatch' >&2; exit 2; }
printf '%s  %s\n' "$expected_sha256" "$archive" | sha256sum -c - >/dev/null

python3 - "$archive" <<'PY'
import os, sys, tarfile
archive = sys.argv[1]
allowed_roots = {"artifacts", "manifests", "commands", "logs"}
seen = set()
regular = set()
with tarfile.open(archive, "r:*") as tf:
    for member in tf.getmembers():
        name = member.name.removeprefix("./")
        parts = name.split("/")
        if not name or name.startswith("/") or ".." in parts or parts[0] not in allowed_roots:
            raise SystemExit(f"unsafe or unexpected entry: {member.name}")
        if name in seen:
            raise SystemExit(f"duplicate entry: {name}")
        seen.add(name)
        if not (member.isdir() or member.isfile()):
            raise SystemExit(f"non-regular entry: {name}")
        if member.isfile():
            regular.add(name)
required = {
    "manifests/inputs.json", "manifests/toolchain.json", "manifests/dependencies.json", "manifests/outputs.json",
    "commands/build.txt", "logs/cmake-clean-build.log", "logs/mozc-clean-offline-replay.log",
    "logs/input-archive-verification.log", "logs/toolchain-verification.log",
}
if not required.issubset(seen):
    raise SystemExit(f"missing required manifests: {sorted(required - seen)}")
metadata = {name for name in regular if name.split("/", 1)[0] in {"manifests", "commands", "logs"}}
if metadata != required:
    raise SystemExit(f"metadata allowlist mismatch: extra={sorted(metadata-required)}, missing={sorted(required-metadata)}")
PY

mkdir -p "$staging"
tar --no-same-owner --no-same-permissions -xf "$archive" -C "$staging"
python3 - "$staging" "$accepted_manifest" "$combined_apk" <<'PY'
import hashlib, json, os, pathlib, re, sys, zipfile
root = pathlib.Path(sys.argv[1]).resolve()
accepted_path = pathlib.Path(sys.argv[2]).resolve()
apk_path = pathlib.Path(sys.argv[3]).resolve()
if not accepted_path.is_file() or not apk_path.is_file():
    raise SystemExit("accepted manifest or combined APK is missing")
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = json.loads((root / "manifests/outputs.json").read_text())
if manifest.get("schemaVersion") != 2:
    raise SystemExit("unsupported full return manifest version")
binding_paths = {
    "inputsManifestSha256": root / "manifests/inputs.json",
    "toolchainManifestSha256": root / "manifests/toolchain.json",
    "dependenciesManifestSha256": root / "manifests/dependencies.json",
    "buildCommandSha256": root / "commands/build.txt",
    "cmakeBuildLogSha256": root / "logs/cmake-clean-build.log",
    "offlineReplayLogSha256": root / "logs/mozc-clean-offline-replay.log",
}
for key, path in binding_paths.items():
    if manifest.get("bindings", {}).get(key) != sha(path):
        raise SystemExit(f"output binding mismatch: {key}")
expected = {item["path"]: item for item in manifest["files"]}
actual = {}
for path in (root / "artifacts").rglob("*"):
    if path.is_file():
        rel = path.relative_to(root).as_posix()
        actual[rel] = {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
if set(actual) != set(expected):
    raise SystemExit("output manifest set mismatch")
for rel, value in actual.items():
    item = expected[rel]
    if value["bytes"] != item["bytes"] or value["sha256"] != item["sha256"]:
        raise SystemExit(f"output mismatch: {rel}")

# Bind the complete returned set to accepted source-set outputs and then to
# the exact combined APK.  Both mappings are total and reject extras.
accepted = json.loads(accepted_path.read_text())
formal = accepted.get("formalBuildEvidence", {})
expected_manifest_hashes = {
    "inputsManifestSha256": formal.get("inputsManifestSha256"),
    "toolchainManifestSha256": formal.get("toolchainManifestSha256"),
    "dependenciesManifestSha256": formal.get("dependenciesManifestSha256"),
}
for key, expected_hash in expected_manifest_hashes.items():
    if not expected_hash or manifest.get("bindings", {}).get(key) != expected_hash:
        raise SystemExit(f"accepted formal binding mismatch: {key}")
for key in ("buildCommandSha256", "cmakeBuildLogSha256", "offlineReplayLogSha256"):
    if not formal.get(key) or manifest.get("bindings", {}).get(key) != formal[key]:
        raise SystemExit(f"accepted formal binding mismatch: {key}")
if sha(root / "manifests" / "outputs.json") != formal.get("outputsManifestSha256"):
    raise SystemExit("accepted outputs manifest hash mismatch")
inputs = json.loads((root / "manifests" / "inputs.json").read_text())
accepted_archive = accepted.get("sourceInputArchive", {})
if inputs.get("schemaVersion") != 2 or inputs.get("archive") != {
    "name": accepted_archive.get("name"),
    "bytes": accepted_archive.get("bytes"),
    "sha256": accepted_archive.get("sha256"),
}:
    raise SystemExit("input archive identity is not bound to accepted manifest")
if sha(root / "manifests" / "dependencies.json") != accepted.get("dependencyClosure", {}).get("repositoryCacheManifestSha256"):
    raise SystemExit("dependency cache manifest is not bound to accepted manifest")
by_return = {}
by_apk = {}
for item in accepted["outputs"]:
    source = pathlib.Path(item["path"]).as_posix()
    marker = "spikes/native-engine-smoke/app/src/main/"
    if marker not in source:
        raise SystemExit(f"unexpected accepted output path: {source}")
    suffix = source.split(marker, 1)[1]
    if suffix.startswith("jniLibs/"):
        returned = "artifacts/jniLibs/" + suffix.removeprefix("jniLibs/")
    elif suffix.startswith("assets/engine-data/"):
        returned = "artifacts/engine-data/" + suffix.removeprefix("assets/engine-data/")
    else:
        raise SystemExit(f"unmapped accepted output: {source}")
    by_return[returned] = item
    by_apk[item["apkPath"]] = item
if set(by_return) != set(expected):
    raise SystemExit("return output set does not equal accepted manifest")
for rel, item in by_return.items():
    returned = expected[rel]
    if (returned["bytes"], returned["sha256"]) != (item["bytes"], item["sha256"]):
        raise SystemExit(f"accepted manifest mismatch: {rel}")
with zipfile.ZipFile(apk_path) as zf:
    engine_entries = {n for n in zf.namelist() if n.startswith("lib/") or n.startswith("assets/engine-data/")}
    if engine_entries != set(by_apk):
        raise SystemExit("combined APK engine entry set mismatch")
    for name, item in by_apk.items():
        digest = hashlib.sha256()
        size = 0
        with zf.open(name) as stream:
            while chunk := stream.read(1024 * 1024):
                size += len(chunk); digest.update(chunk)
        if size != item["bytes"] or digest.hexdigest() != item["sha256"]:
            raise SystemExit(f"combined APK binding mismatch: {name}")
for path in root.rglob("*"):
    relative = path.relative_to(root)
    # Binary artifacts are authenticated by the exact output manifest.  Text
    # redaction applies only to the metadata/log surfaces that can leak a
    # command line or credential; decoding arbitrary model data creates false
    # positives from coincidental bytes.
    if path.is_file() and relative.parts[0] in {"manifests", "commands", "logs"}:
        text = path.read_text(errors="ignore")
        banned = [r"BEGIN (?:OPENSSH|RSA|EC) PRIVATE KEY", r"(?:token|password|secret)\s*[=:]", r"(?:ssh|scp)\s+[^\s]+@"]
        if any(re.search(pattern, text, re.I) for pattern in banned):
            raise SystemExit(f"credential/redaction scan failed: {relative}")
print("builder return archive verification passed")
PY
