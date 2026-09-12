#!/usr/bin/env python3
"""T9 九宫格 device gate: digit keyface -> librime digit-prism -> candidates.

The T9 schema (scripts/generate-t9-schema.py) xlit-maps every pinyin syllable
to its digit string; the lattice enumerates ambiguous splits (9426 = xian /
xi'an) and ranks by frequency. This suite drives the REAL keyboard: switch to
九宫格 via the long-press mode menu, type digits on the rendered grid, and
assert candidates/commit through the host editor oracle.
"""
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])

import device_verify as d
import fv_common as shared

RESULTS = []


def record(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name +
          (f"  [{detail}]" if detail else ""), flush=True)


def ev(expr):
    return d.devtools_eval(expr)


def tick(label):
    print(f"[t {time.time():.0f}] {label}", flush=True)


def digit_keys_present():
    keys = ev(
        "[...document.querySelectorAll('#qwertyLayer .kb-key')]"
        ".map(k => k.dataset.key)"
    ) or []
    return all(str(n) in keys for n in range(1, 10)), keys


def preedit_text():
    return ev("document.getElementById('preeditLine').textContent") or ""


def t9_geometry():
    """Digit-key geometry straight from the live DOM. key_geometry()'s
    qwerty completeness gate (>=27 letter keys) can never pass on the t9
    keyface, so fresh_kb() returns None here and the digits must come from
    the raw DevTools read (t9 reuses the qwertyLayer container)."""
    shared.refresh_keyboard_geometry()
    return d.devtools_key_geometry() or {}


def type_digits(geo, digits, wait=0.3):
    for char in digits:
        point = geo.get(char)
        if not point:
            raise RuntimeError(f"t9 geometry has no key for {char!r}")
        d.tap(*point, wait=wait)


def wait_candidates(attempts=8):
    for _ in range(attempts):
        cands = d.devtools_candidates()
        if cands:
            return cands
        time.sleep(0.5)
    return []


def main():
    d.prepare()
    kb = d.fresh_kb(refocus=True)
    if not kb:
        raise SystemExit("keyboard geometry unavailable")

    d.reset_shift(kb)
    d.switch_mode(kb, "九宫格 T9")
    time.sleep(1.5)  # 首次切 T9 要建 luna_pinyin_t9 会话
    geo = t9_geometry()
    tick("t9: mode switched")

    present, keys = digit_keys_present()
    record("t9: digit keyface rendered", present,
           f"keys={[k for k in keys if k and len(k) == 1][:12]}")

    # 94664 = zhong（也允许其它音节切分）。清场后输入。
    d.clear_field(kb)
    type_digits(geo, "94664")
    time.sleep(0.8)
    cands = wait_candidates()
    has_zhong = any(any(ch in text for ch in "中种重众钟忠") for text in cands)
    record("t9: 94664 reaches zhong-family candidates",
           bool(cands) and has_zhong, f"candidates={cands[:6]}")
    preedit = preedit_text()
    record("t9: digits echo on the preedit line",
           "94664" in preedit or "94664" in " ".join(cands),
           f"preedit={preedit!r}")
    tick("t9: zhong typed")

    # 歧义切分：9426 = xian / xi'an，两类候选都该出现。
    d.clear_field(kb)
    type_digits(geo, "9426")
    time.sleep(0.8)
    cands2 = wait_candidates()
    joined = "".join(cands2)
    has_xian = any(ch in joined for ch in "先县现线限显")
    has_xian_split = any(ch in joined for ch in "西吸希息") and any(
        ch in joined for ch in "安按岸案")
    record("t9: 9426 lattice offers xian readings",
           bool(cands2) and (has_xian or has_xian_split),
           f"candidates={cands2[:6]}")
    tick("t9: ambiguous split typed")

    # 空格上屏首候选（宿主编辑器为 oracle）。
    space = geo.get("<space>")
    if not space:
        record("t9: space commits the pool head", False, "no <space> geometry")
        sys.exit(1)  # 没有空格几何无法继续；直接非零退出，别绕过失败汇总
    d.tap(*space, wait=0.2)
    time.sleep(0.8)
    committed = (d.field_text_retry() or "").strip()
    record("t9: space commits the pool head", bool(committed),
           f"field={committed!r}")
    tick("t9: committed")

    # 回全拼，模式记忆 + 布局复原。
    d.switch_mode(kb, "全拼 Pinyin")
    kb = d.fresh_kb() or kb
    letters_ok = ev('[...document.querySelectorAll("[data-key=q]")].length > 0')
    record("t9: switch back restores qwerty", bool(letters_ok))

    failed = [name for name, ok, _ in RESULTS if not ok]
    passed = len(RESULTS) - len(failed)
    print(f"\n== t9 device suite: {passed}/{len(RESULTS)} passed ==")
    if failed:
        print("failures: " + " | ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()
