#!/usr/bin/env python3
"""Device gates (height card / editor strip / settings pages / languages).

This suite deliberately keeps JavaScript read-only.  It may inspect live DOM
rects and state through DevTools, but every interaction that this batch is
checking is sent as an adb input tap/swipe.  In particular, the height card,
phrase card, mode menu, candidate picks, settings navigation and JSON editor
backspaces must exercise the same touch/InputConnection paths as a user.

The native IME-picker persistence path is owned by the separate native gate;
this file covers the keyboard/settings interactions requested for .
"""
import json
import math
import os
import re
import sys
import time
from xml.etree import ElementTree

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import device_verify as d


RESULTS = []
MODE_LABELS = {
    "英文 Direct": ("En",),
    "全拼 Pinyin": ("拼", "PY"),
    "双拼": ("双", "DP"),
    "Français": ("FR",),
}
MODE_TITLES = {
    "英文 Direct": ("英文 Direct", "English"),
    "全拼 Pinyin": ("全拼 Pinyin", "Pinyin"),
    "双拼": ("双拼", "Double Pinyin"),
    "Français": ("Français",),
    "Русский": ("Русский", "Russian"),
    "日本語 Romaji": ("日本語 Romaji", "Japanese"),
}
QUICK_PAIR_KEY = "feelime_quick_pair"
DEFAULT_QUICK_PAIR = ("pinyin", "direct")
MODE_ID_TITLES = {
    "direct": MODE_TITLES["英文 Direct"],
    "pinyin": MODE_TITLES["全拼 Pinyin"],
    "double-pinyin": MODE_TITLES["双拼"],
    "french": MODE_TITLES["Français"],
    "russian": MODE_TITLES["Русский"],
    "japanese": MODE_TITLES["日本語 Romaji"],
}
MODE_ID_LABELS = {
    "direct": ("En",),
    "pinyin": ("拼", "PY"),
    "double-pinyin": ("双", "DP"),
    "french": ("FR",),
    "russian": ("РУ",),
    "japanese": ("日", "JP"),
}


def record(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name +
          (f"  [{detail}]" if detail else ""), flush=True)


def ev(expression):
    """Read the keyboard WebView through the existing DevTools helper."""
    return d.devtools_eval(expression)


def sev(expression):
    """Read the settings WebView through the existing DevTools helper."""
    return d.devtools_eval_target("settings/index.html", expression)


def wait_until(read, predicate=lambda value: bool(value), timeout=8.0,
               interval=0.25):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = read()
        except Exception:
            last = None
        if predicate(last):
            return last
        time.sleep(interval)
    return last


def screen_size():
    values = re.findall(r"(\d+)x(\d+)", d.shell("wm size"))
    if not values:
        return 1080, 2400
    return tuple(int(value) for value in values[-1])


def _quoted(value):
    return json.dumps(value, ensure_ascii=False)


def keyboard_rect(selector):
    expression = (
        "(() => { const el = document.querySelector(" + _quoted(selector) + ");"
        " if (!el || el.hidden) return null;"
        " const r = el.getBoundingClientRect();"
        " return {left:r.left, top:r.top, width:r.width, height:r.height}; })()"
    )
    value = ev(expression)
    if not value or value.get("width", 0) <= 0 or value.get("height", 0) <= 0:
        return None
    return value


def keyboard_text_rect(text, selector="button", contains=False):
    expression = (
        "(() => { const wanted = " + _quoted(text) + ";"
        " const el = [...document.querySelectorAll(" + _quoted(selector) + ")]"
        "   .find(node => "
        + ("node.textContent.includes(wanted)" if contains
           else "node.textContent.trim() === wanted") + ");"
        " if (!el || el.hidden) return null;"
        " const r = el.getBoundingClientRect();"
        " return {left:r.left, top:r.top, width:r.width, height:r.height}; })()"
    )
    value = ev(expression)
    if not value or value.get("width", 0) <= 0 or value.get("height", 0) <= 0:
        return None
    return value


def refresh_keyboard_geometry():
    # Height previews and IME opening move the native WebView. Its origin
    # in a cached DevTools description is no longer a valid touch target.
    if d._DT_SOCKET is not None:
        d._DT_SOCKET.close()
    d._DT_SOCKET = None


def keyboard_point(selector):
    refresh_keyboard_geometry()
    rect = keyboard_rect(selector)
    if not rect:
        return None
    return (
        round((rect["left"] + rect["width"] / 2) * d._DT_SCALE + d._DT_OFFSET[0]),
        round((rect["top"] + rect["height"] / 2) * d._DT_SCALE + d._DT_OFFSET[1]),
    )


def keyboard_text_point(text, selector="button", contains=False):
    refresh_keyboard_geometry()
    rect = keyboard_text_rect(text, selector, contains=contains)
    if not rect:
        return None
    return (
        round((rect["left"] + rect["width"] / 2) * d._DT_SCALE + d._DT_OFFSET[0]),
        round((rect["top"] + rect["height"] / 2) * d._DT_SCALE + d._DT_OFFSET[1]),
    )


def keyboard_text_point_any(texts, selector="button", contains=False):
    for text in texts:
        point = keyboard_text_point(text, selector, contains=contains)
        if point:
            return point
    return None


def keyboard_tap(selector, wait=0.45):
    point = keyboard_point(selector)
    if not point:
        raise RuntimeError(f"keyboard selector not visible: {selector}")
    d.tap(*point, wait=wait)


def keyboard_text_tap(text, selector="button", contains=False, wait=0.45):
    point = keyboard_text_point(text, selector, contains=contains)
    if not point:
        raise RuntimeError(f"keyboard text not visible: {text!r} in {selector}")
    d.tap(*point, wait=wait)


def keyboard_text_tap_any(texts, selector="button", contains=False, wait=0.45):
    point = keyboard_text_point_any(texts, selector, contains=contains)
    if not point:
        raise RuntimeError(f"keyboard text not visible: {texts!r} in {selector}")
    d.tap(*point, wait=wait)


def keyboard_long_press(selector, hold_ms=650):
    point = keyboard_point(selector)
    if not point:
        raise RuntimeError(f"keyboard selector not visible: {selector}")
    x, y = point
    # Same-point input swipe is Android's real long-press injection.  No
    # TouchEvent is synthesized in the page.
    d.shell(f"input swipe {x} {y} {x} {y} {int(hold_ms)}", timeout=15)
    time.sleep(0.8)


def keyboard_swipe(selector, start_fraction=0.12, end_fraction=0.88,
                   duration_ms=650):
    refresh_keyboard_geometry()
    rect = keyboard_rect(selector)
    if not rect:
        raise RuntimeError(f"keyboard selector not visible: {selector}")
    left = rect["left"] + rect["width"] * start_fraction
    right = rect["left"] + rect["width"] * end_fraction
    y = rect["top"] + rect["height"] / 2
    x1 = round(left * d._DT_SCALE + d._DT_OFFSET[0])
    x2 = round(right * d._DT_SCALE + d._DT_OFFSET[0])
    py = round(y * d._DT_SCALE + d._DT_OFFSET[1])
    d.shell(f"input swipe {x1} {py} {x2} {py} {int(duration_ms)}", timeout=15)
    time.sleep(0.9)


def keyboard_state():
    return ev(
        "(() => ({"
        " mode: document.querySelector('#modeToggle .cn-main')?.textContent || '',"
        " sub: document.querySelector('#modeToggle .cn-sub')?.textContent || '',"
        " preedit: document.getElementById('preeditLine')?.textContent || '',"
        " expanded: !document.getElementById('expandLayer')?.hidden,"
        " composing: document.body.classList.contains('composing'),"
        " modeMenu: document.getElementById('modeMenu')?.classList.contains('open'),"
        " panel: document.getElementById('panelLayer')?.hidden === false"
        "}))()") or {}


def wait_mode(label):
    labels = (label,) if isinstance(label, str) else tuple(label)
    return wait_until(
        lambda: ev("document.querySelector('#modeToggle .cn-main')?.textContent || ''"),
        lambda value: value in labels, timeout=10.0)


def saved_quick_pair():
    """Read the effective pair, including the production default."""
    raw = ev("localStorage.getItem(" + _quoted(QUICK_PAIR_KEY) + ")")
    try:
        pair = json.loads(raw) if raw else list(DEFAULT_QUICK_PAIR)
    except (TypeError, ValueError):
        pair = list(DEFAULT_QUICK_PAIR)
    if not isinstance(pair, list) or len(pair) != 2:
        return list(DEFAULT_QUICK_PAIR)
    if any(not isinstance(mode, str) or mode not in MODE_ID_TITLES
           for mode in pair):
        return list(DEFAULT_QUICK_PAIR)
    return pair


def mode_menu_labels():
    """Map engine ids to the labels rendered for the current UI locale."""
    keyboard_long_press("#modeToggle")
    opened = wait_until(
        lambda: ev("document.getElementById('modeMenu')?.classList.contains('open')"),
        lambda value: value is True, timeout=3.0)
    if not opened:
        return {}
    items = ev(
        "[...document.querySelectorAll('#modeMenu button')].map(button => ({"
        "title:button.querySelector('span:last-child')?.textContent.trim() || '',"
        "label:button.querySelector('.prep')?.textContent.trim() || ''"
        "}))") or []
    keyboard_tap("#modeToggle")
    wait_until(
        lambda: ev("document.getElementById('modeMenu')?.classList.contains('open')"),
        lambda value: value is False, timeout=3.0)
    labels = {}
    for mode, titles in MODE_ID_TITLES.items():
        for item in items:
            if item.get("title", "") in titles and item.get("label") not in ("", "…"):
                labels[mode] = item.get("label", "")
                break
    return labels


def mode_menu_item_point(titles):
    """Return the physical center of the selectable item with this title.

    The menu button contains a shorthand span followed by its translated
    title.  Matching the complete last span keeps a title such as ``English``
    from accidentally selecting another button whose shorthand happens to
    contain the same text.
    """
    refresh_keyboard_geometry()
    payload = ev(
        "(() => { const wanted = " + _quoted(titles) + ";"
        " const item = [...document.querySelectorAll('#modeMenu button')]"
        "   .find(node => {"
        "     const title = node.querySelector('span:last-child')"
        "       ?.textContent.trim() || '';"
        "     return wanted.includes(title);"
        "   });"
        " if (!item || item.classList.contains('preparing')"
        "     || item.classList.contains('current')) return null;"
        " const r = item.getBoundingClientRect();"
        " return {left:r.left, top:r.top, width:r.width, height:r.height};"
        "})()")
    if not payload or payload.get("width", 0) <= 0 or payload.get("height", 0) <= 0:
        return None
    return (
        round((payload["left"] + payload["width"] / 2) * d._DT_SCALE + d._DT_OFFSET[0]),
        round((payload["top"] + payload["height"] / 2) * d._DT_SCALE + d._DT_OFFSET[1]),
    )


def switch_mode_real(title):
    """Open the mode menu with a physical long-press and pick an item."""
    expected = MODE_LABELS[title]
    titles = MODE_TITLES[title]
    current = ev("document.querySelector('#modeToggle .cn-main')?.textContent || ''")
    if current in expected:
        return True
    if ev("document.getElementById('modeMenu')?.classList.contains('open')"):
        keyboard_tap("#modeToggle")
        time.sleep(0.4)
    keyboard_long_press("#modeToggle")
    opened = wait_until(
        lambda: ev("document.getElementById('modeMenu')?.classList.contains('open')"),
        lambda value: value is True, timeout=3.0)
    if not opened:
        return False
    # A mode menu item contains both its shorthand and full title.
    point = None
    for _ in range(20):
        ready = ev(
            "(() => { const wanted = " + _quoted(titles) + ";"
            " const item = [...document.querySelectorAll('#modeMenu button')]"
            "   .find(node => {"
            "     const title = node.querySelector('span:last-child')"
            "       ?.textContent.trim() || '';"
            "     return wanted.includes(title);"
            "   });"
            " return !!item && !item.classList.contains('preparing')"
            "   && !item.classList.contains('current'); })()")
        point = mode_menu_item_point(titles)
        if ready and point:
            break
        time.sleep(0.4)
    if not point:
        return False
    d.tap(*point, wait=0.8)
    return wait_mode(expected) in expected


def switch_mode_id_real(mode_id, expected_label=None):
    """Select a mode by its stable engine id through the real mode menu."""
    titles = MODE_ID_TITLES.get(mode_id, ())
    if not titles:
        return False
    if expected_label:
        current = ev("document.querySelector('#modeToggle .cn-main')?.textContent || ''")
        if current == expected_label:
            return True
    if ev("document.getElementById('modeMenu')?.classList.contains('open')"):
        keyboard_tap("#modeToggle")
        time.sleep(0.4)
    keyboard_long_press("#modeToggle")
    opened = wait_until(
        lambda: ev("document.getElementById('modeMenu')?.classList.contains('open')"),
        lambda value: value is True, timeout=3.0)
    if not opened:
        return False
    point = None
    menu_ready = False
    for _ in range(20):
        menu_ready = ev(
            "(() => { const wanted = " + _quoted(titles) + ";"
            " const item = [...document.querySelectorAll('#modeMenu button')]"
            "   .find(node => {"
            "     const title = node.querySelector('span:last-child')"
            "       ?.textContent.trim() || '';"
            "     return wanted.includes(title);"
            "   });"
            " return !!item && !item.classList.contains('preparing')"
            "   && !item.classList.contains('current'); })()")
        point = mode_menu_item_point(titles)
        if menu_ready and point:
            break
        time.sleep(0.4)
    if not menu_ready or not point:
        keyboard_tap("#modeToggle")
        return False
    d.tap(*point, wait=0.8)
    if expected_label:
        return wait_mode(expected_label) == expected_label
    return wait_until(
        lambda: ev("!document.getElementById('modeMenu')?.classList.contains('open')"),
        lambda value: value is True, timeout=10.0) is True


def type_word_adb(keyboard, word, wait=0.28):
    for char in word:
        point = keyboard.get(char)
        if not point:
            raise RuntimeError(f"key geometry has no key for {char!r}")
        d.tap(*point, wait=wait)


def keyboard_key_dimensions():
    return ev(
        "(() => { const out = {};"
        " document.querySelectorAll('#qwertyLayer [data-key]').forEach(el => {"
        "   const r = el.getBoundingClientRect();"
        "   out[el.dataset.key] = {left:r.left, top:r.top, width:r.width, height:r.height};"
        " }); return out; })()") or {}


def reset_input(keyboard):
    # This is preparation between cases, not the interaction under test.  The
    # shared helper drains both the engine composition and the host fixture.
    d.clear_field(keyboard)
    time.sleep(0.5)


def height_state():
    return ev(
        "(() => { const card = document.getElementById('heightCard');"
        " const view = document.getElementById('softKeyboard');"
        " const value = (document.getElementById('heightValue')?.textContent || '')"
        "   .match(/\\d+/);"
        " const cr = card.getBoundingClientRect();"
        " const vr = view.getBoundingClientRect();"
        " return {open:card.classList.contains('open') && !card.hidden,"
        " value:value ? Number(value[0]) : null,"
        " keyboardHeight:Math.round(view?.clientHeight || 0),"
        " cardBottom:cr.bottom, keyboardTop:vr.top,"
        " plusDisabled:!!document.getElementById('heightPlus')?.disabled,"
        " minusDisabled:!!document.getElementById('heightMinus')?.disabled,"
        " saveDisabled:!!document.getElementById('heightCardSave')?.disabled}; })()") or {}


def height_card_fits(state):
    try:
        card_bottom = float(state.get("cardBottom", math.nan))
        keyboard_top = float(state.get("keyboardTop", math.nan))
    except (TypeError, ValueError):
        return False
    return bool(state.get("open")) and math.isfinite(card_bottom) \
        and math.isfinite(keyboard_top) and card_bottom <= keyboard_top + 1.5


def case_height_card(keyboard):
    reset_input(keyboard)
    keyboard_tap("#setupButton")
    if not wait_until(lambda: ev("document.getElementById('settingsPanel')?.classList.contains('open')"),
                      lambda value: value is True, timeout=3.0):
        record("height card opens from keyboard settings", False, "settings panel did not open")
        return
    keyboard_text_tap_any(("调节 ›", "Adjust ›"), "#settingsPanel .set-nav", contains=False)
    initial = wait_until(height_state, lambda value: value and value.get("open"), timeout=3.0)
    if not initial or not initial.get("open"):
        record("height card opens from keyboard settings", False, "height card did not open")
        return
    record("height card opens from keyboard settings", True,
           f"value={initial.get('value')}")
    record("height card starts above keyboard keys", height_card_fits(initial),
           f"cardBottom={initial.get('cardBottom')} keyboardTop={initial.get('keyboardTop')}")

    # Fine step buttons are actual adb taps.  Re-read their positions after
    # every step because the keyboard top (and therefore the card) may move.
    if initial.get("plusDisabled"):
        record("+ button changes height by real touch", False, "button disabled")
    else:
        keyboard_tap("#heightPlus")
        plus = wait_until(height_state,
                          lambda value: value and value.get("value") != initial.get("value"),
                          timeout=3.0)
        plus_value = plus.get("value") if plus else None
        plus_ok = isinstance(plus_value, (int, float)) and \
            plus_value > initial.get("value", math.inf)
        record("+ button changes height by real touch",
               plus_ok,
               f"{initial.get('value')}->{plus_value}")
        record("card follows keyboard top after +", height_card_fits(plus or {}),
               f"state={plus}")

    current = height_state()
    if current.get("minusDisabled"):
        record("− button changes height by real touch", False, "button disabled")
    else:
        keyboard_tap("#heightMinus")
        minus = wait_until(height_state,
                           lambda value: value and value.get("value") != current.get("value"),
                           timeout=3.0)
        minus_value = minus.get("value") if minus else None
        minus_ok = isinstance(minus_value, (int, float)) and \
            minus_value < current.get("value", -math.inf)
        record("− button changes height by real touch",
               minus_ok,
               f"{current.get('value')}->{minus_value}")
        record("card follows keyboard top after −", height_card_fits(minus or {}),
               f"state={minus}")

    before_drag = height_state()
    try:
        keyboard_swipe("#heightTrack")
        after_drag = wait_until(height_state,
                                lambda value: value and value.get("value") != before_drag.get("value"),
                                timeout=3.0)
        record("drag strip changes height by real adb swipe",
               bool(after_drag and after_drag.get("value") != before_drag.get("value")),
               f"{before_drag.get('value')}->{after_drag.get('value') if after_drag else None}")
        record("card follows keyboard top after drag",
               height_card_fits(after_drag or {}), f"state={after_drag}")
    except RuntimeError as error:
        record("drag strip changes height by real adb swipe", False, str(error))
        record("card follows keyboard top after drag", False, "no drag state")

    # Cancel is also a real tap.  It must close the card and restore the
    # height saved when editing began, rather than persisting the preview.
    keyboard_tap("#heightCardCancel")
    closed = wait_until(
        lambda: ev("document.getElementById('heightCard')?.hidden"),
        lambda value: value is True, timeout=3.0)
    restored = wait_until(
        lambda: ev("Math.round(document.getElementById('softKeyboard')?.clientHeight || 0)"),
        lambda value: isinstance(value, (int, float)) and value == initial.get("keyboardHeight"),
        timeout=3.0)
    record("cancel closes card and rolls back preview",
           closed is True and restored == initial.get("keyboardHeight"),
           f"hidden={closed} initial={initial.get('keyboardHeight')} restored={restored}")
    ev("window.Feelime && window.Feelime.resetToHome && window.Feelime.resetToHome()")


def case_phrase_card(keyboard):
    reset_input(keyboard)
    if not switch_mode_real("英文 Direct"):
        record("phrase card setup reaches Direct mode", False, "mode switch failed")
        return
    before = keyboard_key_dimensions()
    keyboard_tap("#favoritesButton")
    if not wait_until(lambda: ev("document.getElementById('panelLayer')?.hidden === false"),
                      lambda value: value is True, timeout=3.0):
        record("phrase editor opens from keyboard favorites", False, "favorites panel did not open")
        return
    keyboard_tap("#panelManage")
    opened = wait_until(lambda: ev("document.getElementById('phraseCard')?.classList.contains('open')"),
                        lambda value: value is True, timeout=3.0)
    after = keyboard_key_dimensions()
    common = sorted(set(before) & set(after))
    changed = []
    for key in common:
        for field in ("left", "top", "width", "height"):
            if abs(float(before[key][field]) - float(after[key][field])) > 1.0:
                changed.append(f"{key}.{field}")
    record("phrase editor opens as a floating card", opened is True,
           f"open={opened}")
    record("phrase card leaves key dimensions unchanged",
           bool(opened) and not changed,
           f"changed={changed[:8]}")
    if opened:
        keyboard_tap("#phraseCardCancel")
        wait_until(lambda: ev("document.getElementById('phraseCard')?.hidden"),
                   lambda value: value is True, timeout=3.0)
        if ev("document.getElementById('panelLayer')?.hidden === false"):
            keyboard_tap("#panelClose")
    ev("window.Feelime && window.Feelime.resetToHome && window.Feelime.resetToHome()")


def case_mode_and_pinyin(keyboard):
    # Supersedes the old "last explicit mode" rule: a temporary
    # long-press selection returns to the first persisted quick-pair entry,
    # then the second entry.  Resolve labels from the live menu so this stays
    # valid in either UI locale and for a user-configured pair.
    pair = saved_quick_pair()
    labels = mode_menu_labels() if pair else {}
    first_labels = tuple(dict.fromkeys(
        ([labels[pair[0]]] if pair[0] in labels else [])
        + list(MODE_ID_LABELS.get(pair[0], ()))
    )) if pair else ()
    second_labels = tuple(dict.fromkeys(
        ([labels[pair[1]]] if pair[1] in labels else [])
        + list(MODE_ID_LABELS.get(pair[1], ()))
    )) if pair else ()
    third_modes = [mode for mode in MODE_ID_TITLES
                   if mode not in pair and mode in labels]
    third_mode = third_modes[0] if third_modes else None
    third_label = labels.get(third_mode) if third_mode else None
    if pair and third_mode and first_labels and second_labels and third_label:
        switched = switch_mode_id_real(third_mode, third_label)
        saved_after_menu = saved_quick_pair()
        first_state = {}
        second_state = {}
        if switched:
            keyboard_tap("#modeToggle")
            first_state = wait_until(
                keyboard_state,
                lambda value: value.get("mode") in first_labels
                and value.get("sub") in second_labels,
                timeout=10.0) or keyboard_state()
            keyboard_tap("#modeToggle")
            second_state = wait_until(
                keyboard_state,
                lambda value: value.get("mode") in second_labels
                and value.get("sub") in first_labels,
                timeout=10.0) or keyboard_state()
        record("temporary third mode -> saved quick pair first then second",
               switched and saved_after_menu == pair
               and first_state.get("mode") in first_labels
               and first_state.get("sub") in second_labels
               and second_state.get("mode") in second_labels
               and second_state.get("sub") in first_labels
               and saved_quick_pair() == pair,
               f"pair={pair} labels={labels} first={first_state} second={second_state}")
    else:
        record("temporary third mode -> saved quick pair first then second",
               False,
               f"invalid/no-third-mode pair={pair!r} labels={labels!r}")

    if not switch_mode_real("双拼"):
        record("switching Chinese mode commits raw input", False, "Double mode unavailable")
        return
    reset_input(keyboard)
    type_word_adb(keyboard, "ni", wait=0.35)
    before = {
        "preedit": ev("document.getElementById('preeditLine')?.textContent || ''"),
        "field": d.field_text_retry() or "",
    }
    switched = switch_mode_real("英文 Direct")
    field = d.field_text_retry() or ""
    record("switching Chinese mode commits raw input",
           switched and before["preedit"].replace(" ", "") == "ni" and field.endswith("ni"),
           f"before={before} afterField={field!r}")
    reset_input(keyboard)


def case_french_candidates(keyboard):
    if not switch_mode_real("Français"):
        record("R3/R12 French candidate case reaches Français", False, "mode switch failed")
        return
    reset_input(keyboard)
    type_word_adb(keyboard, "ete", wait=0.35)
    candidates = wait_until(
        lambda: ev("[...document.querySelectorAll('#candidates .candidate')].map(e => e.textContent)"),
        lambda value: isinstance(value, list) and len(value) > 0, timeout=8.0) or []
    accent_point = keyboard_text_point("ê", "#candidates .candidate")
    if not accent_point:
        # Some model builds expose a different circumflex head; the required
        # regression is still recorded as a missing accent rather than guessed.
        accent_point = keyboard_text_point("é", "#candidates .candidate")
        accent_text = "é"
    else:
        accent_text = "ê"
    if accent_point:
        d.tap(*accent_point, wait=1.0)
        accent_state = keyboard_state()
        preedit = accent_state.get("preedit", "").replace(" ", "")
        record("tapping French accent keeps composing without expanding",
               not accent_state.get("expanded") and preedit.startswith(accent_text),
               f"accent={accent_text} preedit={preedit!r} expanded={accent_state.get('expanded')}")
    else:
        record("tapping French accent keeps composing without expanding",
               False, f"candidates={candidates[:8]}")

    reset_input(keyboard)
    type_word_adb(keyboard, "ete", wait=0.35)
    first = wait_until(
        lambda: ev("document.querySelector('#candidates .candidate')?.textContent || ''"),
        lambda value: bool(value), timeout=8.0)
    if first:
        keyboard_tap("#candidates .candidate:first-child", wait=1.0)
        text = d.field_text_retry() or ""
        record("French candidate selection commits a trailing space",
               text.endswith(" "), f"candidate={first!r} field={text!r}")
    else:
        record("French candidate selection commits a trailing space",
               False, "no French candidate")
    reset_input(keyboard)
    switch_mode_real("英文 Direct")


def settings_payload(selector):
    expression = (
        "(() => { const el = document.querySelector(" + _quoted(selector) + ");"
        " if (!el || el.hidden) return null; const r = el.getBoundingClientRect();"
        " return {left:r.left, top:r.top, width:r.width, height:r.height,"
        " innerWidth:window.innerWidth, innerHeight:window.innerHeight,"
        " dpr:window.devicePixelRatio || 1, screenX:window.screenX || 0,"
        " screenY:window.screenY || 0, title:document.title}; })()"
    )
    return sev(expression)


def settings_geometry(selector):
    payload = settings_payload(selector)
    if not payload or payload.get("width", 0) <= 0 or payload.get("height", 0) <= 0:
        return None
    inner_w = float(payload.get("innerWidth") or 0)
    if not inner_w:
        return None
    try:
        root = ElementTree.fromstring(d.ui_dump())
    except ElementTree.ParseError:
        return None
    for node in root.iter("node"):
        if (node.get("class") != "android.webkit.WebView"
                or node.get("text") != payload.get("title")):
            continue
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if not bounds:
            continue
        left, top, right, bottom = map(int, bounds.groups())
        if right <= left or bottom <= top:
            continue
        # Native debug editors and the IME can shrink the settings WebView.
        # Its own accessibility bounds define the origin; CSS pixels scale
        # uniformly and must never be stretched to the whole screen height.
        scale = (right - left) / inner_w
        x = left + (payload["left"] + payload["width"] / 2) * scale
        y = top + (payload["top"] + payload["height"] / 2) * scale
        visible_top = max(top, top + payload["top"] * scale)
        visible_bottom = min(bottom, top + (payload["top"] + payload["height"]) * scale)
        if visible_bottom - visible_top >= min(payload["height"], 24) * scale:
            y = (visible_top + visible_bottom) / 2
        return (round(x), round(y)), (left, top, right, bottom)
    return None


def settings_point(selector):
    geometry = settings_geometry(selector)
    return geometry[0] if geometry else None


def settings_tap(selector, wait=0.7, scroll=True):
    # An open IME plus the native test editor can leave only 200 CSS pixels
    # for the page, so reaching controls below the model rows takes >4 swipes.
    for _ in range(12):
        geometry = settings_geometry(selector)
        if not geometry:
            time.sleep(0.5)
            continue
        point, (left, top, right, bottom) = geometry
        width, height = right - left, bottom - top
        if left <= point[0] < right and top <= point[1] < bottom:
            d.tap(*point, wait=wait)
            return True
        if not scroll:
            break
        # Scroll the settings page with the real Android gesture until the
        # requested DOM node enters the viewport.
        x = (left + right) // 2
        if point[1] < top:
            d.shell(f"input swipe {x} {top + int(height * .25)} "
                    f"{x} {top + int(height * .75)} 260", timeout=15)
        else:
            d.shell(f"input swipe {x} {top + int(height * .78)} "
                    f"{x} {top + int(height * .22)} 260", timeout=15)
        time.sleep(0.6)
    print(f"settings_tap: scroll did not reveal {selector}; geometry={geometry}", flush=True)
    return False


def settings_visible_pages():
    return sev("[...document.querySelectorAll('.page')].filter(p => !p.hidden)"
               ".map(p => p.dataset.page)") or []


def settings_home_text():
    return sev("document.querySelector('[data-page=\"home\"]')?.innerText || ''") or ""


def wait_settings_ready():
    return wait_until(
        lambda: sev("!!window.FeelimeSettings && !!window.FeelimeSettings.showPage"),
        lambda value: value is True, timeout=12.0, interval=0.5)


def launch_settings(with_fixtures=False):
    extra = " --ez com.feelime.ime.extra.SHOW_DEBUG_FIXTURES true" if with_fixtures else ""
    d.shell(f"am start -n {d.PKG}/.SetupActivity{extra}")
    time.sleep(1.5)
    ready = wait_settings_ready()
    if not ready:
        return ready
    # am start RESUMES the activity with whatever sub-page an earlier suite
    # left open (9i parks settings on the input-test page, its editor still
    # focused and the page scrolled). Reset the router, drop focus (the
    # browser otherwise scroll-anchores back toward the focused editor) and
    # WAIT until the scroll truly settles - a moving page makes every
    # geometry read a stale snapshot and the taps land on other entries.
    sev("(() => { if (document.activeElement && document.activeElement.blur)"
        " document.activeElement.blur();"
        " window.FeelimeSettings.showPage('home');"
        " window.scrollTo(0, 0); return 'ok'; })()")
    stable = 0
    for _ in range(12):
        if str(sev("window.scrollY")) in ("0", "0.0"):
            stable += 1
            if stable >= 2:
                break
        else:
            stable = 0
            sev("window.scrollTo(0, 0)")
        time.sleep(0.4)
    return ready


def case_settings_and_json(keyboard):
    # Launch without the automation-only fixture extra: the home page must not
    # expose a debug editor or any management surface.
    if not launch_settings(with_fixtures=False):
        record("R4/R5/R7 settings home opens", False, "settings WebView unavailable")
        return
    home = settings_home_text()
    pages = settings_visible_pages()
    debug_nodes = "feelime-test-input" in d.ui_dump()
    # 1.0.4's backup entry subtitle ("设置 · 常用语 · 词库") legitimately
    # MENTIONS 常用语 - the old home-text substring check false-positived on
    # it. The actual R4/R5/R7 target is management ENTRIES on the home page,
    # so match entry labels, not free text.
    duplicate_management = sev(
        "[...document.querySelectorAll('[data-page=\"home\"] button[data-target]')]"
        ".some(b => /^（?(剪贴板|常用语)/.test(b.textContent.trim()))")
    # The about ENTRY subtitle legitimately says 版本信息; the old card flaw
    # was a copy button + rows table on the home page - check for those.
    no_version_card = (sev("!!document.querySelector('[data-page=\"home\"] #aboutRows, "
                           "[data-page=\"home\"] #btnCopyAbout')") is False)
    record("R4/R5/R7 settings home has no debug/version/duplicate management",
           pages == ["home"] and not debug_nodes and no_version_card
           and duplicate_management is False,
           f"pages={pages} version={no_version_card} debug={debug_nodes} duplicate={duplicate_management}")

    # The height-card steps leave the IME open; an open keyboard squeezes
    # the settings WebView and swallows the taps aimed at the home entries
    # (geometry maps below the window bottom, input swipe lands on keys).
    # Close it first - the JSON editor step reopens it via the focus tap.
    for _ in range(3):
        if "mInputShown=true" not in d.shell(
                "dumpsys input_method | grep -m1 mInputShown"):
            break
        d.shell("input keyevent 4")
        time.sleep(0.8)
    opened_input = settings_tap('button[data-target="input"]')
    input_page = wait_until(settings_visible_pages,
                            lambda value: value == ["input"], timeout=4.0)
    record("settings group opens through a real tap", opened_input and input_page == ["input"],
           f"pages={input_page}")
    back_input = settings_tap('[data-page="input"] [data-back]')
    home_again = wait_until(settings_visible_pages,
                            lambda value: value == ["home"], timeout=4.0)
    record("settings sub-page back returns to home through a real tap",
           back_input and home_again == ["home"], f"pages={home_again}")

    # Open the input page again and seed only test data through JS.  Focus and
    # every deletion below still travel through a physical tap on the actual
    # Feelime backspace key and the settings WebView's native InputConnection.
    settings_tap('button[data-target="input"]')
    wait_until(settings_visible_pages, lambda value: value == ["input"], timeout=4.0)
    seeded = sev(
        "(() => { const ta = document.getElementById('customJson');"
        " if (!ta) return false; ta.value = '{\"version\":1,\"rows\":[["
        "{\"t\":\"alpha\",\"tap\":\"alpha\"}]]}';"
        " ta.dispatchEvent(new Event('input', {bubbles:true})); return ta.value.length; })()")
    focused = settings_tap("#customJson", wait=1.0)
    kb = wait_until(lambda: d.key_geometry(), lambda value: bool(value), timeout=8.0)
    value_before = sev("document.getElementById('customJson')?.value || ''") or ""
    # The caret placement is preparation after the real focus tap; no bridge
    # or native proxy is wrapped, and all deletes below are adb input taps.
    sev("(() => { const ta = document.getElementById('customJson');"
        " if (!ta) return false; ta.focus();"
        " ta.setSelectionRange(ta.value.length, ta.value.length); return true; })()")
    timings = []
    if kb:
        for _ in range(10):
            start = time.monotonic()
            d.tap(*kb["<backspace>"], wait=0.05)
            timings.append(time.monotonic() - start)
    value_after = sev("document.getElementById('customJson')?.value || ''") or ""
    max_latency = max(timings) if timings else float("inf")
    record("JSON editor accepts continuous real backspaces",
           bool(seeded) and focused and bool(kb) and len(value_after) < len(value_before),
           f"seed={seeded} focused={focused} before={len(value_before)} after={len(value_after)}")
    record("each JSON backspace stays below the 2s regression threshold",
           bool(timings) and max_latency < 2.0,
           f"latencies={[round(value, 3) for value in timings]} max={max_latency:.3f}")
    d.shell("input keyevent 4")
    time.sleep(1.0)


def main():
    # Start from portrait and the stock keyboard height so a previous suite
    # cannot make the height range appear capped.
    d.shell("settings put system accelerometer_rotation 0")
    d.shell("settings put system user_rotation 0")
    d.shell("run-as com.feelime.ime sh -c 'rm -f shared_prefs/feelime_keyboard.xml'")
    d.shell("am force-stop com.feelime.ime")
    time.sleep(1.0)
    d.prepare()
    keyboard = d.fresh_kb(refocus=True)
    if not keyboard:
        raise SystemExit("keyboard geometry unavailable")
    ev("window.Feelime && window.Feelime.resetToHome && window.Feelime.resetToHome()")
    time.sleep(0.6)

    case_height_card(keyboard)
    keyboard = d.fresh_kb(refocus=True) or keyboard
    case_phrase_card(keyboard)
    keyboard = d.fresh_kb(refocus=True) or keyboard
    case_mode_and_pinyin(keyboard)
    keyboard = d.fresh_kb(refocus=True) or keyboard
    case_french_candidates(keyboard)
    case_settings_and_json(keyboard)

    failed = [name for name, ok, _ in RESULTS if not ok]
    passed = len(RESULTS) - len(failed)
    print(f"\n== height-card device suite: {passed}/{len(RESULTS)} passed ==")
    if failed:
        print("failures: " + " | ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()
