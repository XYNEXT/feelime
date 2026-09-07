#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
inputs="${FEELIME_NATIVE_INPUTS:?set FEELIME_NATIVE_INPUTS to the verified public archive directory}"
destination="${1:?usage: $0 OUTPUT_TAR}"
[[ ! -e "$destination" ]] || { echo "destination already exists: $destination" >&2; exit 2; }

archives=(
  librime-417db2385f732cb0fa194b497042c42abb897d99.tar.gz
  rime-prelude-082425ea0684bca36474415d4a0e8db9b016487e.tar.gz
  rime-essay-e9b1a374a6ea015fca5bdd04318924b4483ac35a.tar.gz
  rime-luna-pinyin-56b934b099dfbeab842320f13aa8b461a6ab3e42.tar.gz
  glog-7b134a5c82c0c0b5698bb6bf7a835b230c5638e4.tar.gz
  yaml-cpp-2f86d13775d119edbb69af52e5f566fd65c6953b.tar.gz
  leveldb-99b3c03b3284f5886f9ef9a4ef703d57373e61be.tar.gz
  marisa-trie-3e87d53b78e15f2f43783d5e376561a8c9722051.tar.gz
  opencc-e9f3bb3fa1d058fe2fd4f096503222854bdc31ab.tar.gz
  boost-1.89.0-cmake.tar.xz
  hunspell-f143a42a0b95578c39f8657101624ed44dea6514.tar.gz
  libreoffice-dictionaries-32b006a2c22a4ac7e8ed3f03346f7b3d85a970a4.tar.gz
  mozc-851c3fe33060d2a6090363e4d7ec44fafde2c03d.tar.gz
)

staging="$(mktemp -d)"
trap 'find "$staging" -depth -delete' EXIT
mkdir -p "$staging/public-inputs" "$staging/feelime/scripts/research" \
  "$staging/feelime/spikes/native-engine-smoke/native-src" \
  "$staging/feelime/spikes/native-engine-smoke/original-schemas"
for archive in "${archives[@]}"; do
  [[ -f "$inputs/$archive" ]] || { echo "missing input: $archive" >&2; exit 2; }
  cp "$inputs/$archive" "$staging/public-inputs/"
done
cp "$repo_root"/scripts/research/{build-cmake-native-engines.sh,build-mozc-android.sh,verify-builder-environment.sh,generate-prefix-index.py,create-full-builder-return.sh,bind-cache-modes.py} "$staging/feelime/scripts/research/"
cp -R "$repo_root/spikes/native-engine-smoke/native-src/." "$staging/feelime/spikes/native-engine-smoke/native-src/"
cp "$repo_root/spikes/native-engine-smoke/original-schemas/ziranma_double_pinyin.schema.yaml" \
  "$staging/feelime/spikes/native-engine-smoke/original-schemas/"

(cd "$staging" && find public-inputs feelime -type f -print0 | sort -z | xargs -0 sha256sum) > "$staging/inputs.sha256"
(cd "$staging" && find public-inputs feelime -type f -printf '%p\t%s\n' | sort) > "$staging/inputs.entries"
tar --sort=name --mtime='UTC 2026-08-24' --owner=0 --group=0 --numeric-owner -cf "$destination" -C "$staging" .
sha256sum "$destination"
stat -c '%s bytes' "$destination"
