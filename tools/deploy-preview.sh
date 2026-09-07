#!/usr/bin/env bash
# 把真实键盘文件 + 预览工具同步到 nginx share（keyboard-preview.html 的
# kb-preview-src/ 布局）。改完键盘源码后跑一次，浏览器刷新即见最新。
set -euo pipefail
DEST="${1:-$HOME/tmp/feelime-preview}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$DEST/kb-preview-src" "$DEST/settings-preview-src"
cp "$ROOT"/app/src/main/assets/keyboard/{index.html,keyboard.css,keyboard.js,VERSION} \
    "$DEST/kb-preview-src/"
cp "$ROOT"/tools/keyboard-preview.html "$DEST/keyboard-preview.html"
cp "$ROOT"/app/src/main/assets/settings/{index.html,settings.css,settings.js} "$DEST/settings-preview-src/"
cp "$ROOT/tools/settings-preview.html" "$DEST/settings-preview.html"
for file in index.html settings.css settings.js; do
    cmp "$ROOT/app/src/main/assets/settings/$file" "$DEST/settings-preview-src/$file"
done
cmp "$ROOT/tools/settings-preview.html" "$DEST/settings-preview.html"
for file in index.html keyboard.css keyboard.js VERSION; do
    cmp "$ROOT/app/src/main/assets/keyboard/$file" "$DEST/kb-preview-src/$file"
done
cmp "$ROOT/tools/keyboard-preview.html" "$DEST/keyboard-preview.html"
echo "deployed and verified against source: $DEST"
