#!/usr/bin/env bash
set -euo pipefail

inputs="${FEELIME_NATIVE_INPUTS:?set FEELIME_NATIVE_INPUTS to the verified archive directory}"
ndk="${FEELIME_NATIVE_NDK:?set FEELIME_NATIVE_NDK to the verified Android NDK r29 directory}"
work="${FEELIME_NATIVE_WORK:?set FEELIME_NATIVE_WORK to a new empty build directory}"
bazelisk="${FEELIME_NATIVE_BAZELISK:?set FEELIME_NATIVE_BAZELISK to pinned Bazelisk 1.29.0}"
ndk_archive="${FEELIME_NATIVE_NDK_ARCHIVE:?set FEELIME_NATIVE_NDK_ARCHIVE to the verified NDK r29 archive}"

[[ "$(uname -m)" == x86_64 ]] || { echo "the pinned native build requires x86_64" >&2; exit 2; }
[[ ! -e "$work" ]] || { echo "FEELIME_NATIVE_WORK must not already exist: $work" >&2; exit 2; }
grep -q 'Pkg.Revision = 29.0.14206865' "$ndk/source.properties" || {
  echo "unexpected NDK identity" >&2; exit 2;
}
printf '%s  %s\n' \
  5a408715e932c0250d28bd84555f12edbf70117de42f9181691c736eacc4a992 \
  "$bazelisk" | sha256sum -c -
printf '%s  %s\n' \
  4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf \
  "$ndk_archive" | sha256sum -c -

mkdir -p "$work/src/mozc" "$work/artifacts"
tar -xf "$inputs/mozc-851c3fe33060d2a6090363e4d7ec44fafde2c03d.tar.gz" \
  --strip-components=1 -C "$work/src/mozc"
cd "$work/src/mozc/src"

# Seed the exact upstream cache name so update_deps.py verifies/extracts the
# already pinned NDK instead of downloading a second copy.
mkdir -p third_party_cache
cp "$ndk_archive" third_party_cache/android-ndk-r29-linux.zip
python3 build_tools/update_deps.py --noninja --noqt --nollvm --nomsys2 --nowix
export ANDROID_NDK_HOME="$ndk"
export BAZELISK_VERIFY_SHA256=422e7a1690b76d7e615c29091d3aca28d0bd3a93fe3c93cbefb8f72d774926d5
repository_cache="$work/dependency-cache"
mkdir -p "$repository_cache" "$work/first-pass"
repository_cache_seed="${FEELIME_NATIVE_REPOSITORY_CACHE_SEED:-}"
repository_cache_manifest="${FEELIME_NATIVE_REPOSITORY_CACHE_MANIFEST:-}"
build_pass() {
  local output_root="$1"
  shift
  "$bazelisk" --output_user_root="$output_root" build package \
    --config oss_android --config release_build --repository_cache="$repository_cache" "$@"
  "$bazelisk" --output_user_root="$output_root" build //data_manager/oss:mozc.data \
    --config release_build --repository_cache="$repository_cache" "$@"
}
if [[ -n "$repository_cache_seed" ]]; then
  [[ -d "$repository_cache_seed/content_addressable/sha256" ]] || {
    echo "invalid FEELIME_NATIVE_REPOSITORY_CACHE_SEED" >&2; exit 2;
  }
  [[ -f "$repository_cache_manifest" ]] || {
    echo "FEELIME_NATIVE_REPOSITORY_CACHE_MANIFEST is required with a seed" >&2; exit 2;
  }
  python3 - "$repository_cache_seed" "$repository_cache_manifest" "$repository_cache" <<'PY'
import hashlib, json, os, pathlib, shutil, stat, sys
root = pathlib.Path(sys.argv[1]).resolve()
manifest = json.loads(pathlib.Path(sys.argv[2]).read_text())
destination = pathlib.Path(sys.argv[3]).resolve()
expected = {item["path"]: item for item in manifest["entries"]}
for rel, item in expected.items():
    path = root / rel
    output = destination / rel
    output.parent.mkdir(parents=True, exist_ok=True)
    if item["type"] == "symlink":
        if not path.is_symlink():
            raise SystemExit(f"missing cache symlink: {rel}")
        target = os.readlink(path)
        resolved = (path.parent / target).resolve()
        if pathlib.Path(target).is_absolute() or not resolved.is_relative_to(root):
            raise SystemExit(f"unsafe cache symlink: {rel} -> {target}")
        if target != item["target"]:
            raise SystemExit(f"cache symlink mismatch: {rel}")
        output.symlink_to(target)
    else:
        if not path.is_file() or path.is_symlink():
            raise SystemExit(f"missing cache file: {rel}")
        actual = {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        if actual["bytes"] != item["bytes"] or actual["sha256"] != item["sha256"]:
            raise SystemExit(f"cache file mismatch: {rel}")
        expected_mode = int(item["mode"], 8)
        if stat.S_IMODE(path.stat().st_mode) != expected_mode:
            raise SystemExit(f"cache file mode mismatch: {rel}")
        shutil.copyfile(path, output)
        output.chmod(expected_mode)
print(f"repository cache manifest verified and materialized: {len(expected)} entries")
PY
  online_root=""
else
  build_pass "$work/bazel-online"
  online_root="$work/bazel-online"
fi

if [[ -n "$online_root" ]]; then
  native_zip="$(find -L bazel-bin -type f -name native_libs.zip -print -quit)"
  data_file="$(find -L bazel-bin -type f -name mozc.data -print -quit)"
  [[ -n "$native_zip" && -n "$data_file" ]] || {
    echo "expected Mozc online outputs were not produced" >&2; exit 1;
  }
  cp "$native_zip" "$work/first-pass/native_libs.zip"
  cp "$data_file" "$work/first-pass/mozc.data"
fi

# A separate Bazel output root contains no materialized external repositories.
# repository_disable_download makes any cache miss a hard failure, so this is
# a true offline replay even when the builder itself has network connectivity.
build_pass "$work/bazel-offline" --repository_disable_download
native_zip="$(find -L bazel-bin -type f -name native_libs.zip -print -quit)"
data_file="$(find -L bazel-bin -type f -name mozc.data -print -quit)"
cp "$native_zip" "$work/artifacts/native_libs.zip"
cp "$data_file" "$work/artifacts/mozc.data"
if [[ -n "$online_root" ]]; then
  cmp "$work/first-pass/native_libs.zip" "$work/artifacts/native_libs.zip"
  cmp "$work/first-pass/mozc.data" "$work/artifacts/mozc.data"
fi

if [[ -n "$repository_cache_manifest" ]]; then
  cp "$repository_cache_manifest" "$work/artifacts/dependency-cache.json"
else
python3 - "$repository_cache" > "$work/artifacts/dependency-cache.json" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
files = []
for path in sorted(root.rglob("*")):
    if path.is_file():
        files.append({
            "path": path.relative_to(root).as_posix(),
            "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        })
print(json.dumps({"schemaVersion": 1, "files": files}, indent=2))
PY
fi
sha256sum "$work/artifacts/native_libs.zip" "$work/artifacts/mozc.data"
