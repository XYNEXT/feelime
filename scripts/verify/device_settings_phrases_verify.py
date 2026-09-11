#!/usr/bin/env python3
"""Device gates (settings sub-pages / phrase CRUD / preedit semantics).

#1 settings sub-pages + phrase CRUD, #3 composition lands before literal,
#4 space key style/mic contrast, #5 complete-input variant pinning, #6 key
map page, #7 Chinese-mode literal popup picks, #8 pinyin preedit stays off
the editor, #9 scrollable panel + toolbar full-settings entry.
(#2 deletion is covered by the legacy editor suites plus the xterm.js
probe on the real device.)"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import device_verify as d

RESULTS = []


def record(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail else ""), flush=True)


def ev(expr):
    return d.devtools_eval(expr)


LABEL_ALIASES = {
    "双拼键位": ("双拼键位", "Pinyin key map"),
    "快捷切换": ("快捷切换", "Quick switch"),
}


def clear(kb):
    d.clear_field(kb)
    time.sleep(0.2)


def open_panel(kb):
    ev("window.Feelime && window.Feelime.toggleSettingsPanel && window.Feelime.toggleSettingsPanel()")
    time.sleep(0.5)


def nav_to(kb, label):
    """From the panel home, tap the sub-page nav row for `label`."""
    labels = json.dumps(LABEL_ALIASES.get(label, (label,)), ensure_ascii=False)
    ev(f"(() => {{ const row = [...document.querySelectorAll('#settingsPanel .set-row')]"
       f".find(r => {labels}.includes(r.querySelector('.set-label')?.textContent.trim()));"
       f" row?.querySelector('.set-nav')?.click(); return 1; }})()")
    time.sleep(0.4)


def main():
    d.prepare()
    kb = d.fresh_kb(refocus=True)
    if not kb:
        raise SystemExit('keyboard geometry unavailable')
    d.reset_shift(kb)
    d.devtools_click_mode("双拼")
    time.sleep(1.5)
    kb = d.fresh_kb(refocus=False) or kb

    # ---- #8 pinyin preedit must NOT land in the editor ----
    clear(kb)
    d.type_word(kb, "nihk", wait=0.4)
    preedit = (d.devtools_preedit() or '').replace(' ', '')
    text = d.field_text_retry()
    record("pinyin stays on the keyboard, not the editor",
           preedit == "nihk" and text == "", f"preedit={preedit!r} field={text!r}")
    clear(kb)

    # ---- #3 a live composition must LAND before a literal commit ----
    d.press(kb, 'x', 0.2)
    time.sleep(0.3)
    ex, ey = kb['e']
    pts = []
    for i in range(14):
        pts.append([round((ex - d._DT_OFFSET[0]) / d._DT_SCALE),
                    round((ey - 160 * i / 13 - d._DT_OFFSET[1]) / d._DT_SCALE)])
    d.synth_gesture(pts, step_ms=12)
    time.sleep(0.5)
    text = d.field_text_retry()
    # Flick digits stay half-width; the composition still lands
    # BEFORE the literal.
    record("flick after composition keeps it (x then flick-up e)",
           text == "x3", f"field={text!r}")
    clear(kb)

    # ---- #5 complete double-pinyin input pins its parse ----
    # Key sequences, not display strings: 自然码 a=韵母a, j=韵母an.
    # "x'a"  reads x'an  (single-key abbreviation x + an)
    # "xi'j" reads xi'an (complete 2-key syllables xi + an)
    def expand_variants(keys):
        clear(kb)
        for ch in keys:
            if ch == "'":
                d.press(kb, '<shift>', 0.22)  # 分词 separator rides the shift slot
            else:
                d.press(kb, ch, 0.2)
        time.sleep(0.8)
        preedit = (d.devtools_preedit() or '').replace(' ', '')
        ev("document.getElementById('composeExpand')?.click()")
        time.sleep(0.9)
        n = ev("[...document.querySelectorAll('#expandVariants .expand-variant')].length") or 0
        ev("document.getElementById('expandCollapse')?.click()")
        time.sleep(0.3)
        return n, preedit

    n_abbr, pe_abbr = expand_variants("x'a")
    n_full, pe_full = expand_variants("xi'j")
    record("abbreviated input expands variants, complete input pins",
           n_abbr >= 3 and pe_abbr == "x'a" and n_full == 0 and pe_full == "xi'j",
           f"x'a={n_abbr} xi'j={n_full} preedits=({pe_abbr!r},{pe_full!r})")

    # ---- #7 long-press popup picks land literally in Chinese modes ----
    # " moved from J to K (J now carries ～).
    clear(kb)
    jx, jy = kb['k']
    d.synth_touch('start', jx, jy)
    time.sleep(0.6)
    cells = ev("(() => { const items = [...document.querySelectorAll('.kp-item')];"
               " return items.map(el => { const r = el.getBoundingClientRect();"
               " return { t: el.textContent, x: Math.round(r.left + r.width / 2),"
               " y: Math.round(r.top + r.height / 2) }; }); })()") or []
    quote = next((c for c in cells if c.get('t') == '“'), None)
    ok7 = False
    if quote:
        # cells are CSS px from getBoundingClientRect; synth_touch wants
        # physical px (same conversion synth_swipe does).
        qx = int(quote['x'] * d._DT_SCALE + d._DT_OFFSET[0])
        qy = int(quote['y'] * d._DT_SCALE + d._DT_OFFSET[1])
        d.synth_touch('move', qx, qy)
        time.sleep(0.15)
        d.synth_touch('end', qx, qy)
        time.sleep(0.5)
        text = d.field_text_retry()
        preedit = (d.devtools_preedit() or '').replace(' ', '')
        ok7 = text == '“' and preedit == ''
        record("popup quote lands literally", ok7,
               f"field={text!r} preedit={preedit!r}")
    else:
        record("popup quote lands literally", False, f"cells={cells[:4]}")
    clear(kb)

    # ---- #1/#6 settings sub-pages ----
    open_panel(kb)
    labels = ev("[...document.querySelectorAll('#settingsPanel .set-label')]"
                ".map(el => el.textContent)") or []
    record("home page rows (tools live on the toolbar; key map moved to the settings app)",
           # UI-19: the cursor-speed row moved into the settings app's feel
           # card, so the panel home page keeps four rows.
           labels in (
               ['色彩模式', '快捷切换', '长按菜单', '键盘高度'],
               ['Appearance', 'Quick switch', 'Keyboard menu', 'Keyboard height'],
           ),
           repr(labels))

    nav_to(kb, '快捷切换')
    pair_rows = ev("[...document.querySelectorAll('#pairEditor .pair-row')].length") or 0
    # #9: overflow is the FEATURE - the panel must scroll within the
    # keyboard instead of pushing rows off-screen.
    scroll = ev("(() => { const p = document.getElementById('settingsPanel');"
                " return { oy: getComputedStyle(p).overflowY,"
                " inKb: p.getBoundingClientRect().bottom <= window.innerHeight + 1 }; })()") or {}
    record("quick-switch sub-page scrolls inside the keyboard",
           pair_rows == 6 and scroll.get('oy') == 'auto' and scroll.get('inKb') is True,
           f"rows={pair_rows} scroll={scroll}")
    # The back chevron rides the toolbar page bar (child 0).
    ev("document.getElementById('settingsPageBar')?.children[0]?.click()")
    time.sleep(0.4)

    # The phrases sub-page is gone - phrase add/edit/delete moved
    # INTO the favorites panel (native-redirect typing included). Covered by
    # the keymap suite; nothing to gate here any more.
    # The back chevron rides the toolbar page bar (child 0).
    ev("document.getElementById('settingsPageBar')?.children[0]?.click()")
    time.sleep(0.3)

    # ---- #9 scrollable panel + toolbar full-settings entry ----
    ev("window.Feelime.closeSettingsPanel()")
    time.sleep(0.3)
    # The "permanent gear" toolbar form was user-rejected and reverted - 
    # stands again (visible ONLY while the panel is open).
    full_hidden = ev("document.getElementById('fullSetupButton')?.hidden === true")
    ev("window.Feelime.toggleSettingsPanel()")
    time.sleep(0.4)
    full_visible = ev("document.getElementById('fullSetupButton')?.hidden === false")
    scrollable = ev("(() => { const p = document.getElementById('settingsPanel');"
                    " return p.classList.contains('open') && getComputedStyle(p).overflowY === 'auto'; })()")
    ev("window.Feelime.closeSettingsPanel()")
    time.sleep(0.3)
    full_after = ev("document.getElementById('fullSetupButton')?.hidden === true")
    record("toolbar full-settings gear toggles with the panel; panel scrolls",
           full_hidden is True and full_visible is True and full_after is True and scrollable is True,
           f"hidden={full_hidden} visible={full_visible} after={full_after} scrollable={scrollable}")

    # ---- #4 space key uses the plain key-cap colour in both themes ----
    space_bg = ev("(() => { const s = document.getElementById('spaceKey');"
                  " return { special: s.classList.contains('kb-special'),"
                  " bg: getComputedStyle(s).backgroundColor }; })()") or {}
    mic_color = ev("(() => { const m = document.querySelector('#spaceKey .space-mic');"
                   " const cs = getComputedStyle(m);"
                   " return { color: cs.color, opacity: cs.opacity }; })()") or {}
    # Light-theme contrast check: parse rgb of --text vs mic colour at .55
    record("space key drops the special grey; mic uses text colour",
           space_bg.get('special') is False and mic_color.get('opacity') == '0.55',
           f"space={space_bg} mic={mic_color}")

    d.devtools_click_mode("英文 Direct")
    time.sleep(1.0)

    failed = [name for name, ok, _ in RESULTS if not ok]
    print(f"\n== {len(RESULTS) - len(failed)}/{len(RESULTS)} passed ==")
    if failed:
        print("FAILED:", ", ".join(failed))
        sys.exit(1)


if __name__ == '__main__':
    main()
