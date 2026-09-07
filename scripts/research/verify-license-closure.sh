#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
manifest="$repo_root/third_party/manifest.json"
for tool in jq readelf c++filt rg; do command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 2; }; done

jq -e '
  ([.sources[].name] + [.originalComponents[].name] + ["android-ndk"]) as $known |
  all(.outputs[]; all(.sources[]; . as $source | $known | index($source))) and
  all(.sources[]; (.licenseFiles | length) > 0 and (.apkPaths | length) > 0 and
      (.correspondingSource | length) > 0 and (.buildRecipe | length) > 0)
' "$manifest" >/dev/null

while IFS= read -r license; do
  [[ -f "$repo_root/$license" ]] || { echo "missing license file: $license" >&2; exit 1; }
done < <(jq -r '.sources[].licenseFiles[]' "$manifest" | sort -u)

for abi in arm64-v8a x86_64; do
  rime="$repo_root/spikes/native-engine-smoke/app/src/main/jniLibs/$abi/libfeelime_rime.so"
  mozc="$repo_root/spikes/native-engine-smoke/app/src/main/jniLibs/$abi/libmozc.so"
  symbols="$(readelf -Ws "$rime" | c++filt)"
  rg -q 'rapidjson::' <<<"$symbols"
  rg -q 'opencc::' <<<"$symbols"
  jq -e --arg path "lib/$abi/libfeelime_rime.so" '
    .outputs[] | select(.apkPath == $path) |
    (.sources | contains(["librime","boost","glog","yaml-cpp","leveldb","marisa-trie","opencc","rapidjson"]))
  ' "$manifest" >/dev/null
  jq -e --arg path "lib/$abi/libmozc.so" '
    .outputs[] | select(.apkPath == $path) |
    (.sources | contains(["mozc","abseil-cpp","protobuf","zlib"]))
  ' "$manifest" >/dev/null
done

# The only RapidJSON material in the verified OpenCC source is the embedded
# header subset; JSON_checker is absent and therefore does not enter outputs.
archive="${FEELIME_OPENCC_ARCHIVE:-/tmp/feelime-native-input-closure.MLqa9n/opencc-e9f3bb3fa1d058fe2fd4f096503222854bdc31ab.tar.gz}"
[[ -f "$archive" ]] || { echo "set FEELIME_OPENCC_ARCHIVE to the verified OpenCC archive" >&2; exit 2; }
tar -tf "$archive" | rg '/deps/rapidjson-1.1.0/rapidjson/rapidjson.h$' >/dev/null
if tar -tf "$archive" | rg -i '/bin/jsonchecker/' >/dev/null; then
  echo 'unexpected JSON_checker content in embedded OpenCC source' >&2
  exit 1
fi

echo 'license and static-component closure passed'
