#!/usr/bin/env bash
set -euo pipefail

cmake_work="${FEELIME_CMAKE_WORK:?set FEELIME_CMAKE_WORK}"
mozc_work="${FEELIME_MOZC_WORK:?set FEELIME_MOZC_WORK}"
input_archive="${FEELIME_INPUT_ARCHIVE:?set FEELIME_INPUT_ARCHIVE}"
dependencies="${FEELIME_DEPENDENCIES_MANIFEST:?set FEELIME_DEPENDENCIES_MANIFEST}"
toolchain_manifest="${FEELIME_TOOLCHAIN_MANIFEST:?set FEELIME_TOOLCHAIN_MANIFEST}"
output="${1:?usage: $0 NEW_RETURN_TAR}"
[[ ! -e "$output" ]] || { echo "return archive exists: $output" >&2; exit 2; }

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/artifacts/jniLibs" "$stage/artifacts/engine-data" \
  "$stage/manifests" "$stage/commands" "$stage/logs"

for abi in arm64-v8a x86_64; do
  mkdir -p "$stage/artifacts/jniLibs/$abi"
  cp "$cmake_work/artifacts/$abi/"{libc++_shared.so,libfeelime_rime.so,libfeelime_hunspell.so,libfeelime_smoke.so} \
    "$stage/artifacts/jniLibs/$abi/"
done
mkdir -p "$stage/artifacts/engine-data/rime" "$stage/artifacts/engine-data/hunspell" \
  "$stage/artifacts/engine-data/mozc"
cp "$cmake_work/artifacts/rime-data/"* "$stage/artifacts/engine-data/rime/"
cp "$cmake_work/artifacts/hunspell/"* "$stage/artifacts/engine-data/hunspell/"
cp "$mozc_work/artifacts/mozc.data" "$stage/artifacts/engine-data/mozc/"
unzip -p "$mozc_work/artifacts/native_libs.zip" libs/arm64-v8a/libmozc.so \
  > "$stage/artifacts/jniLibs/arm64-v8a/libmozc.so"
unzip -p "$mozc_work/artifacts/native_libs.zip" libs/x86_64/libmozc.so \
  > "$stage/artifacts/jniLibs/x86_64/libmozc.so"

cp "$dependencies" "$stage/manifests/dependencies.json"
cp "$toolchain_manifest" "$stage/manifests/toolchain.json"
cat > "$stage/commands/build.txt" <<'EOF'
verify-builder-environment.sh
build-cmake-native-engines.sh from two independent new empty directories
compare the exact 23-file CMake/native output manifests byte-for-byte
build-mozc-android.sh from a separate new empty directory with --repository_disable_download
create-full-builder-return.sh over only the accepted CMake and Mozc work directories
EOF

python3 - "$stage" "$input_archive" <<'PY'
import hashlib, json, pathlib, sys
root, archive = map(pathlib.Path, sys.argv[1:])
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
inputs = {
    "schemaVersion": 2,
    "archive": {"name": archive.name, "bytes": archive.stat().st_size, "sha256": sha(archive)},
    "allowlist": {
        "entriesSha256": sha(root.parent / "nonexistent") if False else None,
    },
}
# inputs.entries and inputs.sha256 are authenticated inside the input archive;
# their exact bytes are copied to the return for receiver-side rechecking.
import tarfile
with tarfile.open(archive) as tf:
    for source, target in (("./inputs.entries", "inputEntries"), ("./inputs.sha256", "inputHashes")):
        data = tf.extractfile(source).read()
        inputs["allowlist"][target + "Sha256"] = hashlib.sha256(data).hexdigest()
        inputs["allowlist"][target + "Bytes"] = len(data)
inputs["allowlist"].pop("entriesSha256", None)
(root / "manifests/inputs.json").write_text(json.dumps(inputs, indent=2) + "\n")
PY

cp "${FEELIME_CMAKE_LOG:?set FEELIME_CMAKE_LOG}" "$stage/logs/cmake-clean-build.log"
cp "${FEELIME_MOZC_LOG:?set FEELIME_MOZC_LOG}" "$stage/logs/mozc-clean-offline-replay.log"
cp "${FEELIME_INPUT_LOG:?set FEELIME_INPUT_LOG}" "$stage/logs/input-archive-verification.log"
cp "${FEELIME_TOOLCHAIN_LOG:?set FEELIME_TOOLCHAIN_LOG}" "$stage/logs/toolchain-verification.log"

python3 - "$stage" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
files = []
for path in sorted((root / "artifacts").rglob("*")):
    if path.is_file():
        files.append({"path": path.relative_to(root).as_posix(), "bytes": path.stat().st_size, "sha256": sha(path)})
bindings = {}
for name in ("inputs", "toolchain", "dependencies"):
    path = root / "manifests" / f"{name}.json"
    bindings[f"{name}ManifestSha256"] = sha(path)
bindings["buildCommandSha256"] = sha(root / "commands/build.txt")
bindings["cmakeBuildLogSha256"] = sha(root / "logs/cmake-clean-build.log")
bindings["offlineReplayLogSha256"] = sha(root / "logs/mozc-clean-offline-replay.log")
manifest = {"schemaVersion": 2, "bindings": bindings, "files": files}
(root / "manifests/outputs.json").write_text(json.dumps(manifest, indent=2) + "\n")
PY

(cd "$stage" && tar --sort=name --mtime='UTC 2026-08-24' --owner=0 --group=0 \
  --numeric-owner -cf "$output" artifacts manifests commands logs)
sha256sum "$output"
stat -c '%s bytes' "$output"
