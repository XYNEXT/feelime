#!/usr/bin/env python3
""" Probe: terminal cursor scrub, terminal mode freedom, phrase rank.

One-off targeted verification of the terminal cursor channel. Requires a
device (FEELIME_ADB_SERIAL) with the current build installed. Modes:

  xterm    com.ohmyterm.mobile (xterm.js WebView host): scrub left must move
           the caret - typed chars land at the new position (screenshots for
           visual read, plus a no-scrub control shot).
  termux   same scrub sequence on the TYPE_NULL path, then mode switching to
           双拼 must be accepted (no toast, chip follows) and 全拼 typing +
           space-pick must commit into the terminal.
  editor   normal-EditText regression: scrub + insert reads back via the IC.
  phrases  favorites rank: a rank-2 phrase lands at candidate slot 2.

Screencaps land in FEELIME_PROBE_OUT (default /tmp/feelime-probes). Restores the
previous default IME and best-effort removes the probe phrase.
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("FEELIME_ADB_SERIAL", "")
import device_verify as d  # noqa: E402

MODE = sys.argv[1] if len(sys.argv) > 1 else "xterm"
OUT = os.environ.get("FEELIME_PROBE_OUT", "/tmp/feelime-probes")
os.makedirs(OUT, exist_ok=True)

APPS = {
    "xterm": "com.ohmyterm.mobile/.MainActivity",
    "termux": "com.termux/.app.TermuxActivity",
}
# Chrome + ttyd stands in for the xterm.js WebView host class: a real page in
# Chromium presents the same hidden-helper-textarea InputConnection that
# WebView-based terminals do (non-TYPE_NULL, keydown-driven terminal).
# FEELIME_B28_URL is required for the web mode (e.g. a local ttyd instance).
WEB_URL = os.environ["FEELIME_B28_URL"]


def shot(name):
    path = d.screenshot(os.path.join(OUT, f"{name}.png"))
    print(f"  shot -> {path}")
    return path


def scrub_left(kb):
    """Left swipe on a letter key (the letter-key scrub surface - NOT space,
    which commits/holds voice). R303 baseline: 260 physical px crosses the
    ~38css slop plus three 12css units, landing the caret at line start."""
    x, y = kb["g"]
    d.synth_swipe(x, y, x - 260, y, 200)
    time.sleep(0.5)


def open_terminal(app):
    d.prepare()
    if app == "web":
        d.shell(
            f'am start -a android.intent.action.VIEW -d "{WEB_URL}" '
            "com.android.chrome")
        time.sleep(6)
    else:
        d.shell(f"am start -n {APPS[app]}")
        time.sleep(3)
    # Short tap to focus the terminal (a long press opens the app menu).
    d.shell("input tap 540 900")
    time.sleep(2)
    kb = d.fresh_kb(refocus=False)
    if not kb:
        print("FAIL keyboard not up")
        raise SystemExit(1)
    # Fresh prompt lines: any stale partial command stays behind us.
    if app != "web":
        for _ in range(2):
            d.shell("input keyevent 66")
            time.sleep(0.4)
    return kb


def restore_mode(start_chip):
    """Best-effort: put the saved mode back the way the probe found it."""
    if not start_chip:
        return
    for title in ("英文 Direct", "全拼 Pinyin", "双拼"):
        if title in start_chip:
            d.devtools_switch_mode(title)
            time.sleep(1.0)
            return


def cleanup_phrase():
    """Remove the probe phrase via the row menu so the user store stays clean."""
    d.devtools_eval(
        "(() => { const b = document.getElementById('favoritesButton');"
        " if (!b) return 'missing'; b.click(); return true; })()")
    time.sleep(0.8)
    removed = d.devtools_eval(
        "(() => { const rows = [...document.querySelectorAll('.panel-item')];"
        " const row = rows.find(r => r.textContent.includes('haha'));"
        " if (!row) return 'absent';"
        " row.querySelector('.panel-more').click();"
        " const menu = document.getElementById('itemMenu');"
        " const buttons = [...menu.querySelectorAll('button')];"
        " const del = buttons[buttons.length - 1];"
        " del.click(); return 'removed'; })()")
    print(f"cleanup: {removed!r}")
    time.sleep(0.8)
    d.devtools_eval(
        "(() => { const c = document.getElementById('panelClose');"
        " if (c) c.click(); return true; })()")


def terminal_scrub_sequence(kb, tag):
    """Type 'abc', scrub left, type 'q'. Scrubbed run must show 'qabc';
    a later control run without scrub shows 'abcq'."""
    d.devtools_switch_mode("英文 Direct")
    time.sleep(0.8)
    d.type_word(kb, "abc")
    time.sleep(0.8)
    shot(f"{tag}-1-abc")
    scrub_left(kb)
    d.type_word(kb, "q")
    time.sleep(0.8)
    shot(f"{tag}-2-scrubbed-q")
    # Control: fresh line, same typing WITHOUT the scrub.
    d.shell("input keyevent 66")
    time.sleep(0.4)
    d.type_word(kb, "abc")
    time.sleep(0.5)
    d.type_word(kb, "q")
    time.sleep(0.8)
    shot(f"{tag}-3-control-abcq")


def main():
    prev_ime = d.shell("settings get secure default_input_method").strip()
    print(f"previous IME: {prev_ime}")
    try:
        if MODE in APPS or MODE == "web":
            kb = open_terminal(MODE)
            print(f"chip: {d.keyboard_chip()!r}")
            terminal_scrub_sequence(kb, MODE)
            if MODE == "termux":
                # Switching away from Direct in a terminal must be
                # ACCEPTED - no rejection toast, chip follows.
                result = d.devtools_switch_mode("双拼")
                time.sleep(2.0)
                chip = d.keyboard_chip()
                toast = d.devtools_eval(
                    "(t => t && t.classList.contains('open') ? t.textContent : '')"
                    "(document.getElementById('toast'))") or ""
                print(f"switch result: {result} chip: {chip!r} toast: {toast!r}")
                ok_switch = "双拼" in (chip or "") and "终端" not in toast
                d.record("termux double-pinyin switch accepted", ok_switch,
                         f"chip={chip!r} toast={toast!r}")
                # 全拼 + space must commit the pool head into the terminal.
                d.devtools_switch_mode("全拼 Pinyin")
                time.sleep(2.0)
                kb = d.fresh_kb(refocus=False) or kb
                d.type_word(kb, "nihao")
                time.sleep(1.5)
                candidates = d.devtools_candidates()
                print(f"candidates: {candidates!r}")
                d.press(kb, "<space>")
                time.sleep(1.0)
                shot("termux-4-pinyin-picked")
                d.devtools_switch_mode("英文 Direct")
        elif MODE == "editor":
            d.prepare()
            kb = d.fresh_kb()
            start_chip = d.keyboard_chip()
            d.devtools_switch_mode("英文 Direct")
            time.sleep(1.0)
            kb = d.fresh_kb() or kb
            d.clear_field(kb)
            d.type_word(kb, "abc")
            time.sleep(0.5)
            scrub_left(kb)
            d.type_word(kb, "q")
            time.sleep(0.6)
            text = d.field_text()
            d.record("editor scrub+insert lands at caret", text == "qabc", repr(text))
            restore_mode(start_chip)
        elif MODE == "phrases":
            d.prepare()
            kb = d.fresh_kb()
            start_chip = d.keyboard_chip()
            d.devtools_switch_mode("英文 Direct")
            time.sleep(1.0)
            kb = d.fresh_kb() or kb
            d.clear_field(kb)
            # Seed a rank-2 phrase through the real card UI (the panel button
            # row lives above the keyboard; keep the IME up so DevTools and
            # the redirect channel stay armed).
            d.devtools_eval(
                "(() => { const b = document.getElementById('favoritesButton');"
                " if (!b) return 'missing'; b.click();"
                " return document.getElementById('panelLayer').hidden; })()")
            time.sleep(0.8)
            d.devtools_eval(
                "(() => { const b = document.getElementById('panelManage');"
                " if (!b) return 'missing'; b.click(); return true; })()")
            time.sleep(0.8)
            d.type_word(kb, "haha")
            time.sleep(0.4)
            # Focus the code field, type the code, nudge rank to 2, save.
            d.devtools_eval(
                "(() => { const c = document.getElementById('phraseCardCode');"
                " c.focus(); return document.activeElement === c; })()")
            time.sleep(0.3)
            d.type_word(kb, "h")
            rank_at = d.devtools_eval(
                "(() => { document.getElementById('phraseCardRankUp').click();"
                " return document.getElementById('phraseCardRankValue').textContent; })()")
            print(f"rank shows: {rank_at!r}")
            d.devtools_eval(
                "(() => { const s = document.getElementById('phraseCardSave');"
                " s.click(); return true; })()")
            time.sleep(1.6)
            # Compose exactly 'h' in 全拼: engine candidates + fav at slot 2.
            d.devtools_switch_mode("全拼 Pinyin")
            time.sleep(1.5)
            kb = d.fresh_kb()
            d.clear_field(kb)
            d.type_word(kb, "h")
            time.sleep(1.5)
            candidates = d.devtools_candidates()
            print(f"candidates: {candidates!r}")
            ok = bool(candidates) and len(candidates) >= 2 and candidates[1] == "haha"
            d.record("rank-2 phrase sits at candidate slot 2", ok, repr(candidates))
            cleanup_phrase()
            restore_mode(start_chip)
        else:
            raise SystemExit(f"unknown mode {MODE}")
    finally:
        # Restore the device's own IME; the probe must not leave Feelime set.
        if prev_ime and "com.feelime" not in prev_ime:
            d.shell(f"ime set {prev_ime}")
        print(f"restored IME: {d.shell('settings get secure default_input_method').strip()}")

    print("done")


if __name__ == "__main__":
    main()
