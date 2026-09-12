#!/usr/bin/env python3
"""Input-prefs device gate (issue #2): 候选字号 + 全拼模糊音.

Both features live on the settings input page and must survive the whole
real chain: a real select/toggle in the settings WebView -> native prefs ->
broadcast -> IME hello / engine-session recreation -> visible behavior.

- 候选字号: the computed font-size of a LIVE candidate element follows the
  tier (100/120/135%) while the row budget stays untouched.
- 模糊音: with the toggle ON the luna_pinyin_fuzzy prism is active - typing
  nian hits lian-family words (n/l initial swap); OFF restores the strict
  prism. The toggle round-trips through the real settings page.
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


def sev(expr):
    return shared.sev(expr)


def engine_prefs_body():
    return d.shell(f"run-as {d.PKG} cat shared_prefs/feelime_engine.xml")


def keyboard_prefs_body():
    return d.shell(f"run-as {d.PKG} cat shared_prefs/feelime_keyboard.xml")


def open_input_page():
    shared.settings_tap('button[data-target="input"]')
    return shared.wait_until(shared.settings_visible_pages,
                             lambda value: value == ["input"], timeout=5.0)


def pref_says(body, pref, value, default):
    """Prefs oracle: an absent key IS the default (a fresh install never
    writes defaults - the boolean/int tiers only materialize on first flip)."""
    if f'name="{pref}"' not in body:
        return value == default
    return f'name="{pref}" value="{value}"' in body


def pick_candidate_font(label):
    """Real system-dialog pick on the 候选字号 select + prefs oracle."""
    from device_feel_degrade_verify import pick_select_option
    d.ensure_keyboard_down()
    if not (shared.launch_settings() and open_input_page()):
        return False
    if not pick_select_option("#candidateFont", label):
        return False
    values = {"100%": "0", "120%": "1", "135%": "2"}
    return bool(shared.wait_until(
        lambda: pref_says(keyboard_prefs_body(), "candidate_font", values[label], "0"),
        lambda value: value is True, timeout=8.0))


def candidate_font_px(kb):
    """Computed font-size of a LIVE candidate element + the bar height
    (user-visible oracle; Direct mode hides the bar so use full pinyin)."""
    d.reset_shift(kb)
    d.switch_mode(kb, "全拼 Pinyin")
    kb = d.fresh_kb() or kb
    d.clear_field(kb)
    d.type_word(kb, "ni", wait=0.28)
    time.sleep(0.8)
    for _ in range(5):
        probe = ev(
            "(() => { const c = document.querySelector('#candidates .candidate');"
            " const bar = document.getElementById('candidates');"
            " return c ? [getComputedStyle(c).fontSize, bar.clientHeight] : null; })()"
        )
        if probe:
            return probe[0], probe[1]
        time.sleep(0.5)
    return None, None


def case_candidate_font():
    if not pick_candidate_font("100%"):
        record("candFont: 100% baseline picked", False)
        return
    kb = d.fresh_kb(refocus=True)
    if not kb:
        record("candFont: keyboard up", False)
        return
    base, bar_base = candidate_font_px(kb)
    dataset0 = ev("document.body.dataset.candFont")

    if not pick_candidate_font("120%"):
        record("candFont: 120% picked", False)
        return
    d.fresh_kb(refocus=True)
    large, bar_large = candidate_font_px(kb)
    dataset1 = ev("document.body.dataset.candFont")

    if not pick_candidate_font("135%"):
        record("candFont: 135% picked", False)
        return
    d.fresh_kb(refocus=True)
    xlarge, bar_xlarge = candidate_font_px(kb)
    dataset2 = ev("document.body.dataset.candFont")

    def px(value):
        return float(str(value).replace("px", "")) if value else 0.0

    record("candFont: tiers land on the live candidate text",
           bool(base and large and xlarge)
           and abs(px(large) - px(base) * 1.2) < 0.6
           and abs(px(xlarge) - px(base) * 1.35) < 0.6
           and (dataset0, dataset1, dataset2) == ("normal", "large", "xlarge"),
           f"base={base} large={large} xlarge={xlarge} "
           f"datasets={(dataset0, dataset1, dataset2)}")

    # Row budget untouched: the candidates bar height must not move.
    record("candFont: bar height unchanged across tiers",
           bar_base == bar_large == bar_xlarge and (bar_base or 0) >= 40,
           f"bars=({bar_base}, {bar_large}, {bar_xlarge})")

    if not pick_candidate_font("100%"):
        record("candFont: restore 100%", False)
        return
    d.fresh_kb(refocus=True)
    restored, _ = candidate_font_px(kb)
    record("candFont: restore to 100%",
           bool(restored) and abs(px(restored) - px(base)) < 0.3,
           f"restored={restored} base={base}")


def set_fuzzy(on):
    """Real settings-page toggle + engine-prefs oracle. No force-stop
    recovery here: killing the app mid-injection wedged the AVD's input
    dispatcher (2026-09-12) - plain retries are enough, the toggle is
    idempotent against its checked state."""
    d.ensure_keyboard_down()
    for attempt in range(3):
        if shared.launch_settings() and open_input_page():
            checked = sev("document.getElementById('fuzzyPinyin')?.checked")
            if checked is not None:
                if bool(checked) != on:
                    sev("document.getElementById('fuzzyPinyin').click()")
                if shared.wait_until(
                        lambda: pref_says(engine_prefs_body(), "fuzzy_pinyin",
                                          "true" if on else "false", "false"),
                        lambda value: value is True, timeout=8.0):
                    return True
        time.sleep(2.0)
    return False


def typed_candidates(kb, word, attempts=3):
    """Type `word` in full-pinyin and return the candidate list (with typing
    retries: the schema swap drops in-flight keys through the same
    STALE_STAMP window as the double-pinyin switch)."""
    d.reset_shift(kb)
    d.switch_mode(kb, "全拼 Pinyin")
    for _ in range(attempts):
        kb2 = d.fresh_kb() or kb
        d.clear_field(kb2)
        d.type_word(kb2, word, wait=0.28)
        time.sleep(0.8)
        for _ in range(5):
            cands = d.devtools_candidates()
            if cands:
                return cands
            time.sleep(0.5)
        kb = kb2
    return []


LIAN_CHARS = "连联莲怜帘恋炼链廉镰"


def case_fuzzy_pinyin():
    if not set_fuzzy(False):
        record("fuzzy: toggle OFF lands the pref", False)
        return
    kb = d.fresh_kb(refocus=True)
    if not kb:
        record("fuzzy: keyboard up", False)
        return
    strict = typed_candidates(kb, "nian")
    strict_has_lian = any(any(ch in text for ch in LIAN_CHARS) for text in strict)
    record("fuzzy OFF: nian stays nian-only",
           bool(strict) and not strict_has_lian, f"candidates={strict[:6]}")

    if not set_fuzzy(True):
        record("fuzzy: toggle ON lands the pref", False)
        return
    kb = d.fresh_kb(refocus=True)
    fuzzy = typed_candidates(kb, "nian")
    fuzzy_has_lian = any(any(ch in text for ch in LIAN_CHARS) for text in fuzzy)
    record("fuzzy ON: nian also hits lian-family words",
           bool(fuzzy) and fuzzy_has_lian, f"candidates={fuzzy[:6]}")

    # 平翘舌 spot check: typing zan should reach zhan-family words too.
    zan = typed_candidates(kb or d.fresh_kb(refocus=True), "zan")
    zan_has_zhan = any(any(ch in text for ch in "沾粘斩展站湛占瞻") for text in zan)
    record("fuzzy ON: zan also hits zhan-family words",
           bool(zan) and zan_has_zhan, f"candidates={zan[:6]}")

    if not set_fuzzy(False):
        record("fuzzy: restore OFF", False)
        return
    d.fresh_kb(refocus=True)
    restored = typed_candidates(d.fresh_kb() or kb, "nian")
    record("fuzzy: restore OFF returns the strict prism",
           bool(restored) and not any(
               any(ch in text for ch in LIAN_CHARS) for text in restored),
           f"candidates={restored[:6]}")


def main():
    d.prepare()
    kb = d.fresh_kb(refocus=True)
    if not kb:
        raise SystemExit("keyboard geometry unavailable")
    case_candidate_font()
    case_fuzzy_pinyin()

    failed = [name for name, ok, _ in RESULTS if not ok]
    passed = len(RESULTS) - len(failed)
    print(f"\n== input-prefs device suite: {passed}/{len(RESULTS)} passed ==")
    if failed:
        print("failures: " + " | ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()
