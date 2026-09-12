#!/usr/bin/env python3
"""Generate engine-data/rime/luna_pinyin_t9.schema.yaml from luna_pinyin.

T9 九宫格（issue #1/#2）：每个拼音音节经 xlit 位置映射变成数字串
（abc→2 … pqrs→7 tuv→8 wxyz→9），prism 里只有数字拼写。歧义切分
（9426 = xian 还是 xi'an）由 librime 的音节图自然枚举，按词频排序——
这正是 T9 的本义。无简拼（单键歧义爆炸），校准 derive 一并去掉。

schema_id luna_pinyin_t9，translator/prism 指向独立命名（fuzzy 先例，
docs/design/double-pinyin.md §2.2 同一构建链）。prism 二进制用 pinned
rime_deployer 离线编译（~/tmp/pinned/build-host/librime/bin/），随
MANIFEST 登记 bytes+sha256。
"""
import pathlib
import sys

SRC = pathlib.Path("app/src/main/assets/engine-data/rime/luna_pinyin.schema.yaml")
DST = pathlib.Path("app/src/main/assets/engine-data/rime/luna_pinyin_t9.schema.yaml")

# 26 个字母 → 9 键位（T9 标准布局：7=PQRS，9=WXYZ）。
XLIT = "xlit/abcdefghijklmnopqrstuvwxyz/22233344455566677778889999/"


def main() -> int:
    text = SRC.read_text(encoding="utf-8")
    start = text.index("speller:")
    end = text.index("alphabet:")
    speller_new = (
        "speller:\n"
        "  algebra:\n"
        "    # 整段音节表 xlit 成数字拼写；无 abbrev（无简拼），\n"
        "    # 校准 derive 全部去掉——数字键面上它们只会制造噪声。\n"
        f"    - \"{XLIT}\"\n"
        "  "
    )
    text = text[:start] + speller_new + text[end:]
    text = text.replace("alphabet: zyxwvutsrqponmlkjihgfedcba",
                        "alphabet: 23456789")
    text = text.replace("schema_id: luna_pinyin\n", "schema_id: luna_pinyin_t9\n")
    text = text.replace(
        "translator:\n  dictionary: luna_pinyin\n",
        "translator:\n  dictionary: luna_pinyin\n  prism: luna_pinyin_t9\n",
    )
    DST.write_text(text, encoding="utf-8")
    print(f"wrote {DST}")
    for needle in ("luna_pinyin_t9", XLIT, "alphabet: 23456789", "abbrev"):
        print(f"  {'has' if needle in text else 'MISSING'} {needle[:40]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
