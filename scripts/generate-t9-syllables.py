#!/usr/bin/env python3
"""Generate the T9 syllable index and inline it into keyboard.js.

数据源：luna_pinyin.table.txt 的 `# - <syllable>` 音节表（424 条，与 APK
内词典同源）。产出「数字串 → 音节」正向索引（T9 布局 xlit）加「首音节数字
串 → 声母」前缀层（左列候选条要展示 m/n 这类未完成读法，docs/design/t9.md
§3）。内联进 keyboard.js 的生成标记块——不新增资产文件（热更包/打包器/
KeyboardAssetStore 都是四文件硬编码，见 2026-09-13 评审记录）。

用法：generate-t9-syllables.py   （幂等，重跑替换标记块）
"""
import pathlib
import re

TABLE = pathlib.Path("app/src/main/assets/engine-data/rime/luna_pinyin.table.txt")
KEYBOARD = pathlib.Path("app/src/main/assets/keyboard/keyboard.js")

XLIT = str.maketrans("abcdefghijklmnopqrstuvwxyz", "22233344455566677778889999")
# 声母表（未完成音节前缀层；zh/ch/sh 是双字母声母，数字串按首字母计——
# 前缀层只提示「还没打完」，具体展开交给完整音节层）。
INITIALS = ["b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
            "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s", "y", "w"]

BEGIN = "// BEGIN GENERATED T9_SYLLABLE_INDEX"
END = "// END GENERATED T9_SYLLABLE_INDEX"


def main() -> int:
    syllables = []
    for line in TABLE.read_text(encoding="utf-8").splitlines():
        m = re.match(r"# - ([a-z]+)\s*$", line)
        if m:
            syllables.append(m.group(1))
        elif syllables and not line.startswith("#"):
            break
    if len(syllables) < 400:
        print(f"syllabary parse suspicious: {len(syllables)} entries", file=sys.stderr)
        return 1

    full = {}
    for s in syllables:
        full.setdefault(s.translate(XLIT), []).append(s)
    pre = {}
    for ini in INITIALS:
        pre.setdefault(ini.translate(XLIT), []).append(ini)

    import json
    payload = json.dumps(
        {"full": full, "pre": pre}, ensure_ascii=False, separators=(",", ":"))
    block = (f"{BEGIN}\n"
             f"    // 由 scripts/generate-t9-syllables.py 生成：数字串 → 音节/\n"
             f"    // 声母前缀（{len(syllables)} 音节，源 luna_pinyin.table.txt）。\n"
             f"    const T9_SYLLABLE_INDEX = {payload};\n"
             f"    {END}\n")

    text = KEYBOARD.read_text(encoding="utf-8")
    pattern = re.compile(re.escape(BEGIN) + r".*?" + re.escape(END) + r"\n", re.S)
    if pattern.search(text):
        text = pattern.sub(block, text)
        action = "replaced"
    else:
        # 挂在 FULL_PINYIN_SYLLABLES 定义之后（同属键盘侧拼音静态数据）。
        anchor = text.index("const FULL_PINYIN_SYLLABLES")
        line_end = text.index("\n", text.index(";", anchor))
        text = text[:line_end + 1] + "\n" + block + text[line_end + 1:]
        action = "inserted"
    KEYBOARD.write_text(text, encoding="utf-8")
    print(f"{action} T9_SYLLABLE_INDEX: {len(syllables)} syllables, "
          f"{len(full)} digit keys, {sum(len(v) for v in pre.values())} prefixes, "
          f"{len(payload)} bytes payload")
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
