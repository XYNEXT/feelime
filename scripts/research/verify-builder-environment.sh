#!/usr/bin/env bash
set -euo pipefail

ndk="${FEELIME_NATIVE_NDK:?set FEELIME_NATIVE_NDK to the verified Android NDK r29 directory}"
ndk_archive="${FEELIME_NATIVE_NDK_ARCHIVE:?set FEELIME_NATIVE_NDK_ARCHIVE to android-ndk-r29-linux.zip}"
bazelisk="${FEELIME_NATIVE_BAZELISK:?set FEELIME_NATIVE_BAZELISK to pinned Bazelisk 1.29.0}"
bazel="${FEELIME_NATIVE_BAZEL:?set FEELIME_NATIVE_BAZEL to pinned Bazel 9.0.2}"

fail() { echo "builder identity mismatch: $*" >&2; exit 2; }
expect_hash() {
  local expected="$1" path="$2"
  [[ -f "$path" ]] || fail "missing $path"
  printf '%s  %s\n' "$expected" "$path" | sha256sum -c - >/dev/null
}
expect_package() {
  local package="$1" expected="$2" actual
  actual="$(dpkg-query -W -f='${Version}' "$package" 2>/dev/null)" || fail "missing package $package"
  [[ "$actual" == "$expected" ]] || fail "$package=$actual (expected $expected)"
}

[[ "$(uname -m)" == x86_64 ]] || fail "uname -m"
[[ "$(uname -r)" == 6.8.0-124-generic ]] || fail "kernel $(uname -r)"
. /etc/os-release
[[ "${ID:-}/${VERSION_ID:-}" == ubuntu/24.04 ]] || fail "OS ${ID:-}/${VERSION_ID:-}"
ldd --version 2>&1 | head -1 | grep -Fq '2.39' || fail "glibc"
[[ "$(python3 --version)" == 'Python 3.12.3' ]] || fail "Python"
g++ -dumpfullversion | grep -Fxq '13.3.0' || fail "g++ version"
g++ -dumpmachine | grep -Fxq x86_64-linux-gnu || fail "g++ target"
java -version 2>&1 | head -1 | grep -Fq '21.0.12' || fail "JDK"
cmake --version | head -1 | grep -Fxq 'cmake version 3.28.3' || fail "CMake"
[[ "$(ninja --version)" == 1.11.1 ]] || fail "Ninja"

expect_hash 1643dacd9feaedc58f3cc581e4d22577dfe25c09b10282936186ccf0f2e61118 /usr/bin/python3
expect_hash 1353e9bdd29a7295c7226bf6c63abccce056d8cac31f112e5cdbecc3f28c2769 /usr/bin/g++
expect_hash 377196a32c5e4442b604bbf36add1c49cfe8680cd27edd693b60872e58895e9e "$(readlink -f "$(command -v java)")"
expect_hash 1c5227af4edd22d8d689def545e18ee458260c0fd579eba2187967f38817e638 /usr/bin/cmake
expect_hash 5965527e09fe2b3787772aa4f711d6a36b393e7f2fcaa744a7a96c5a4ddf59cb /usr/bin/ninja
expect_hash 4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf "$ndk_archive"
[[ "$(stat -c '%s' "$ndk_archive")" == 783549481 ]] || fail "NDK archive length"
expect_hash 5a408715e932c0250d28bd84555f12edbf70117de42f9181691c736eacc4a992 "$bazelisk"
expect_hash 422e7a1690b76d7e615c29091d3aca28d0bd3a93fe3c93cbefb8f72d774926d5 "$bazel"
grep -Fxq 'Pkg.Revision = 29.0.14206865' "$ndk/source.properties" || fail "NDK revision"
file -L "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" | grep -Fq 'x86-64' || fail "NDK host clang"

expect_package libc6 2.39-0ubuntu8.8
expect_package python3 3.12.3-0ubuntu2.1
expect_package gcc 4:13.2.0-7ubuntu1
expect_package g++ 4:13.2.0-7ubuntu1
expect_package cmake 3.28.3-1build7
expect_package ninja-build 1.11.1-2
expect_package openjdk-21-jdk-headless 21.0.12+8-1~24.04

echo 'builder environment verification passed'
