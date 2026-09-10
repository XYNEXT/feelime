#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
inputs="${FEELIME_NATIVE_INPUTS:?set FEELIME_NATIVE_INPUTS to the verified archive directory}"
ndk="${FEELIME_NATIVE_NDK:?set FEELIME_NATIVE_NDK to the verified Android NDK r29 directory}"
work="${FEELIME_NATIVE_WORK:?set FEELIME_NATIVE_WORK to a new empty build directory}"

[[ "$(uname -m)" == x86_64 ]] || { echo "the pinned native build requires x86_64" >&2; exit 2; }
[[ ! -e "$work" ]] || { echo "FEELIME_NATIVE_WORK must not already exist: $work" >&2; exit 2; }
grep -q 'Pkg.Revision = 29.0.14206865' "$ndk/source.properties" || {
  echo "unexpected NDK identity" >&2; exit 2;
}

mkdir -p "$work/src/rime/librime/deps" "$work/src/hunspell" "$work/src/rime-data" "$work/artifacts"
export SOURCE_DATE_EPOCH=1724457600
reproducible_paths="-ffile-prefix-map=$work=/feelime-build -fmacro-prefix-map=$work=/feelime-build"
export CFLAGS="${CFLAGS:-} $reproducible_paths"
export CXXFLAGS="${CXXFLAGS:-} $reproducible_paths"
extract_into() {
  local archive="$1" destination="$2"
  mkdir -p "$destination"
  tar -xf "$inputs/$archive" --strip-components=1 -C "$destination"
}

extract_into librime-417db2385f732cb0fa194b497042c42abb897d99.tar.gz "$work/src/rime/librime"
extract_into glog-7b134a5c82c0c0b5698bb6bf7a835b230c5638e4.tar.gz "$work/src/rime/librime/deps/glog"
extract_into yaml-cpp-2f86d13775d119edbb69af52e5f566fd65c6953b.tar.gz "$work/src/rime/librime/deps/yaml-cpp"
extract_into leveldb-99b3c03b3284f5886f9ef9a4ef703d57373e61be.tar.gz "$work/src/rime/librime/deps/leveldb"
extract_into marisa-trie-3e87d53b78e15f2f43783d5e376561a8c9722051.tar.gz "$work/src/rime/librime/deps/marisa-trie"
extract_into opencc-e9f3bb3fa1d058fe2fd4f096503222854bdc31ab.tar.gz "$work/src/rime/librime/deps/opencc"
extract_into boost-1.89.0-cmake.tar.xz "$work/src/rime/boost"
cp -R "$repo_root/spikes/native-engine-smoke/native-src/rime/." "$work/src/rime/"

for package in prelude essay luna-pinyin; do
  mkdir -p "$work/src/rime-data/$package"
done
extract_into rime-prelude-082425ea0684bca36474415d4a0e8db9b016487e.tar.gz "$work/src/rime-data/prelude"
extract_into rime-essay-e9b1a374a6ea015fca5bdd04318924b4483ac35a.tar.gz "$work/src/rime-data/essay"
extract_into rime-luna-pinyin-56b934b099dfbeab842320f13aa8b461a6ab3e42.tar.gz "$work/src/rime-data/luna-pinyin"
# See patches/luna-pinyin-zh-hans-reset.patch: simplified Chinese is the
# session-start default, which upstream does not pin.
patch --silent -d "$work/src/rime-data/luna-pinyin" -p1 \
  < "$repo_root/scripts/research/patches/luna-pinyin-zh-hans-reset.patch"

extract_into hunspell-f143a42a0b95578c39f8657101624ed44dea6514.tar.gz "$work/src/hunspell/hunspell"
cp -R "$repo_root/spikes/native-engine-smoke/native-src/hunspell/." "$work/src/hunspell/"

# Select the licensed dictionaries and create a deterministic sorted prefix
# index at build time. Runtime lookups never scan the full Hunspell .dic.
dictionary_source="$work/src/dictionaries"
extract_into libreoffice-dictionaries-32b006a2c22a4ac7e8ed3f03346f7b3d85a970a4.tar.gz "$dictionary_source"
mkdir -p "$work/artifacts/hunspell"
cp "$dictionary_source/fr_FR/dictionaries/fr.aff" "$work/artifacts/hunspell/fr.aff"
cp "$dictionary_source/fr_FR/dictionaries/fr.dic" "$work/artifacts/hunspell/fr.dic"
cp "$dictionary_source/ru_RU/ru_RU.aff" "$work/artifacts/hunspell/ru_RU.aff"
cp "$dictionary_source/ru_RU/ru_RU.dic" "$work/artifacts/hunspell/ru_RU.dic"
python3 "$repo_root/scripts/research/generate-prefix-index.py" \
  "$work/artifacts/hunspell/fr.dic" "$work/artifacts/hunspell/fr.prefix.txt"
python3 "$repo_root/scripts/research/generate-prefix-index.py" \
  "$work/artifacts/hunspell/ru_RU.dic" "$work/artifacts/hunspell/ru_RU.prefix.txt"

# Build the deployer from the same pinned source/dependencies as Android, then
# precompile both the upstream full-pinyin schema and Feelime's original
# double-pinyin schema. No schema compilation occurs on the device.
cmake -S "$work/src/rime" -B "$work/build/rime-host" -G Ninja \
  -DFEELIME_HOST_DEPLOYER=ON -DCMAKE_BUILD_TYPE=Release -DENABLE_LOGGING=OFF \
  -DCMAKE_C_FLAGS="$reproducible_paths" -DCMAKE_CXX_FLAGS="$reproducible_paths"
cmake --build "$work/build/rime-host" --target rime_deployer
rime_shared="$work/rime-data/shared"
rime_user="$work/rime-data/user"
rime_build="$work/rime-data/build"
mkdir -p "$rime_shared" "$rime_user" "$rime_build"
cp -R "$work/src/rime-data/prelude/." "$rime_shared/"
cp -R "$work/src/rime-data/essay/." "$rime_shared/"
cp -R "$work/src/rime-data/luna-pinyin/." "$rime_shared/"
cp "$repo_root/spikes/native-engine-smoke/original-schemas/ziranma_double_pinyin.schema.yaml" "$rime_shared/"
cp "$repo_root/spikes/native-engine-smoke/original-schemas/double_pinyin_flypy.schema.yaml" "$rime_shared/"
cp "$repo_root/spikes/native-engine-smoke/original-schemas/double_pinyin_sogou.schema.yaml" "$rime_shared/"
deployer="$work/build/rime-host/librime/bin/rime_deployer"
"$deployer" --compile "$rime_shared/luna_pinyin.schema.yaml" "$rime_user" "$rime_shared" "$rime_build"
"$deployer" --compile "$rime_shared/ziranma_double_pinyin.schema.yaml" "$rime_user" "$rime_shared" "$rime_build"
"$deployer" --compile "$rime_shared/double_pinyin_flypy.schema.yaml" "$rime_user" "$rime_shared" "$rime_build"
"$deployer" --compile "$rime_shared/double_pinyin_sogou.schema.yaml" "$rime_user" "$rime_shared" "$rime_build"
mkdir -p "$work/artifacts/rime-data"
for output in \
  luna_pinyin.table.bin luna_pinyin.prism.bin luna_pinyin.reverse.bin \
  luna_pinyin.prism.txt luna_pinyin.table.txt luna_pinyin.schema.yaml \
  ziranma_double_pinyin.prism.bin ziranma_double_pinyin.prism.txt \
  ziranma_double_pinyin.schema.yaml \
  double_pinyin_flypy.prism.bin double_pinyin_flypy.prism.txt \
  double_pinyin_flypy.schema.yaml \
  double_pinyin_sogou.prism.bin double_pinyin_sogou.prism.txt \
  double_pinyin_sogou.schema.yaml; do
  cp "$rime_build/$output" "$work/artifacts/rime-data/"
done

toolchain="$ndk/build/cmake/android.toolchain.cmake"
for abi in arm64-v8a x86_64; do
  case "$abi" in
    arm64-v8a) target_triple=aarch64-linux-android ;;
    x86_64) target_triple=x86_64-linux-android ;;
  esac
  cmake -S "$work/src/rime" -B "$work/build/rime-$abi" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain" -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="$reproducible_paths" -DCMAKE_CXX_FLAGS="$reproducible_paths" \
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,--build-id=sha1
  cmake --build "$work/build/rime-$abi" --target feelime_rime

  cmake -S "$work/src/hunspell" -B "$work/build/hunspell-$abi" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain" -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="$reproducible_paths" -DCMAKE_CXX_FLAGS="$reproducible_paths" \
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,--build-id=sha1
  cmake --build "$work/build/hunspell-$abi" --target feelime_hunspell

  mkdir -p "$work/artifacts/$abi"
  cp "$work/build/rime-$abi/libfeelime_rime.so" "$work/artifacts/$abi/"
  cp "$work/build/hunspell-$abi/libfeelime_hunspell.so" "$work/artifacts/$abi/"

  cmake -S "$repo_root/spikes/native-engine-smoke/native-src/smoke" \
    -B "$work/build/smoke-$abi" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain" -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="$reproducible_paths" -DCMAKE_CXX_FLAGS="$reproducible_paths" \
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,--build-id=sha1 \
    -DFEELIME_PREBUILT_DIR="$work/artifacts/$abi"
  cmake --build "$work/build/smoke-$abi" --target feelime_smoke

  cp "$work/build/smoke-$abi/libfeelime_smoke.so" "$work/artifacts/$abi/"
  cp "$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$target_triple/libc++_shared.so" \
    "$work/artifacts/$abi/"
done

strip="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
for abi in arm64-v8a x86_64; do
  for library in libfeelime_rime.so libfeelime_hunspell.so libfeelime_smoke.so; do
    "$strip" --strip-unneeded "$work/artifacts/$abi/$library"
  done
done

find "$work/artifacts" -type f -print0 | sort -z | xargs -0 sha256sum
