#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SDK_ROOT=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "${SDK_ROOT}" ]]; then
    echo "ANDROID_HOME or ANDROID_SDK_ROOT is required" >&2
    exit 1
fi
PLATFORM_DIR=${ANDROID_PLATFORM_DIR:-"${SDK_ROOT}/platforms/android-35"}
BUILD_TOOLS_DIR=${ANDROID_BUILD_TOOLS_DIR:-"${SDK_ROOT}/build-tools/35.0.0"}
OUT_DIR=${OUT_DIR:-"${HOME}/tmp/feelime/ui-dump"}
WORK_DIR=${WORK_DIR:-"${OUT_DIR}/.build"}

ANDROID_JAR=${ANDROID_JAR:-"${PLATFORM_DIR}/android.jar"}
UIAUTOMATOR_JAR=${UIAUTOMATOR_JAR:-"${PLATFORM_DIR}/uiautomator.jar"}
D8=${D8:-"${BUILD_TOOLS_DIR}/d8"}

for required in javac jar "${ANDROID_JAR}" "${UIAUTOMATOR_JAR}" "${D8}"; do
    if [[ "${required}" == /* && ! -e "${required}" ]]; then
        echo "missing build input: ${required}" >&2
        exit 1
    fi
    if [[ "${required}" != /* ]] && ! command -v "${required}" >/dev/null 2>&1; then
        echo "missing build tool: ${required}" >&2
        exit 1
    fi
done

rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}/stub-src/junit/framework" "${WORK_DIR}/stub-classes" \
    "${WORK_DIR}/classes" "${WORK_DIR}/dex" "${OUT_DIR}"

# platform-35/uiautomator.jar deliberately refers to the JUnit classes that
# the on-device legacy runner supplies, but the SDK does not ship that runner
# API as a compile-time jar.  Keep this stub compile-only; it is never passed
# to D8 or packaged into the test jar.
cat > "${WORK_DIR}/stub-src/junit/framework/TestCase.java" <<'EOF'
package junit.framework;

public class TestCase {
    protected void setUp() throws Exception {}
}
EOF

javac \
    -source 8 -target 8 -Xlint:-options \
    -classpath "${ANDROID_JAR}:${UIAUTOMATOR_JAR}" \
    -d "${WORK_DIR}/stub-classes" \
    "${WORK_DIR}/stub-src/junit/framework/TestCase.java"

javac \
    -source 8 -target 8 -Xlint:-options \
    -classpath "${ANDROID_JAR}:${UIAUTOMATOR_JAR}:${WORK_DIR}/stub-classes" \
    -d "${WORK_DIR}/classes" \
    "${SCRIPT_DIR}/RepetitiveTest.java" "${SCRIPT_DIR}/UiDumpTest.java"

"${D8}" \
    --min-api 23 \
    --lib "${ANDROID_JAR}" \
    --lib "${UIAUTOMATOR_JAR}" \
    --output "${WORK_DIR}/dex" \
    "${WORK_DIR}/classes/android/test/RepetitiveTest.class" \
    "${WORK_DIR}/classes/com/feelime/verify/uidump/UiDumpTest.class"

rm -f "${OUT_DIR}/feelime-ui-dump.jar"
jar --create --file "${OUT_DIR}/feelime-ui-dump.jar" \
    -C "${WORK_DIR}/dex" classes.dex

sha256sum "${OUT_DIR}/feelime-ui-dump.jar"
echo "jar: ${OUT_DIR}/feelime-ui-dump.jar"
echo "class: com.feelime.verify.uidump.UiDumpTest#testDumpWindowHierarchy"
echo "run: adb shell uiautomator runtest /data/local/tmp/feelime-ui-dump.jar -c com.feelime.verify.uidump.UiDumpTest -e output /sdcard/fv-ui.xml"
