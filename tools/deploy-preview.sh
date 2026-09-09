#!/usr/bin/env bash
# 把真实键盘文件 + 预览工具同步到预览目录（keyboard-preview.html 的
# kb-preview-src/ 布局）。改完键盘源码后跑一次，浏览器刷新即见最新。
#
# 目录解析（demo-first 外观流程的交付步骤，见 AGENTS.md）：
#   1) 显式参数      deploy-preview.sh <目录>
#   2) FEELIME_PREVIEW_ROOT（~/.config/feelime/env.sh，不入库）——
#      发布到 <root>/preview-<键盘版本>/，版本取自 assets/keyboard/VERSION
#   3) 都没有时落 ~/tmp/feelime-preview
# 配了 FEELIME_PREVIEW_URL 时，结束打印可访问链接。预览路径的服务端必须
# 发 Cache-Control: no-cache（demo 同名换内容，长缓存会让确认变成看旧样式）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Machine-local preview target (FEELIME_PREVIEW_ROOT/URL) - same loading
# rules as the build (parse env.sh, explicit environment always wins).
. "$ROOT/scripts/feelime-env.sh"
KB_VERSION=$(tr -d '[:space:]' < "$ROOT/app/src/main/assets/keyboard/VERSION")
if [ $# -ge 1 ]; then
    DEST="$1"
elif [ -n "${FEELIME_PREVIEW_ROOT:-}" ]; then
    DEST="$FEELIME_PREVIEW_ROOT/preview-$KB_VERSION"
else
    DEST="$HOME/tmp/feelime-preview"
fi
mkdir -p "$DEST/kb-preview-src" "$DEST/settings-preview-src"
cp "$ROOT"/app/src/main/assets/keyboard/{index.html,keyboard.css,keyboard.js,VERSION} \
    "$DEST/kb-preview-src/"
cp "$ROOT"/tools/keyboard-preview.html "$DEST/keyboard-preview.html"
cp "$ROOT"/app/src/main/assets/settings/{index.html,settings.css,settings.js} "$DEST/settings-preview-src/"
cp "$ROOT"/tools/settings-preview.html "$DEST/settings-preview.html"
for file in index.html settings.css settings.js; do
    cmp "$ROOT/app/src/main/assets/settings/$file" "$DEST/settings-preview-src/$file"
done
cmp "$ROOT/tools/settings-preview.html" "$DEST/settings-preview.html"
for file in index.html keyboard.css keyboard.js VERSION; do
    cmp "$ROOT/app/src/main/assets/keyboard/$file" "$DEST/kb-preview-src/$file"
done
cmp "$ROOT/tools/keyboard-preview.html" "$DEST/keyboard-preview.html"
echo "deployed and verified against source: $DEST"
if [ -n "${FEELIME_PREVIEW_URL:-}" ]; then
    echo "preview: $FEELIME_PREVIEW_URL/preview-$KB_VERSION/keyboard-preview.html"
fi
