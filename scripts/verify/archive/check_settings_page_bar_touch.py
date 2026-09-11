#!/usr/bin/env python3
"""Check: the settings sub-page chrome (‹ back / × close
in the toolbar page bar) must work with a REAL touch - adb input tap, not a
DevTools synthetic click. Run with FEELIME_ADB_SERIAL=<serial>."""
import sys
import time

sys.path.insert(0, __file__.rsplit('/', 1)[0])
import device_verify as d  # noqa: E402

d.prepare()
kb = d.fresh_kb()
assert kb, 'keyboard not up'

ev = d.devtools_eval


def open_pair():
    # Idempotent: only toggle when the panel is closed (a real back tap
    # leaves it OPEN on the home page).
    state = ev("document.getElementById('settingsPanel').classList.contains('open')")
    if not state:
        ev("(() => { window.Feelime.toggleSettingsPanel(); return 1; })()")
        time.sleep(0.6)
    ev("(() => { const row = [...document.querySelectorAll('#settingsPanel .set-row')]"
       ".find(r => /^(快捷切换|Quick switch)$/.test(r.querySelector('.set-label')?.textContent.trim() || ''));"
       " row?.querySelector('.set-nav')?.click(); return 1; })()")
    time.sleep(0.6)


def tap_dom(selector):
    """Real touchscreen tap at a DOM point (CSS px -> physical px)."""
    pt = ev(f"(() => {{ const el = document.querySelector('{selector}');"
            " if (!el) return null; const r = el.getBoundingClientRect();"
            " return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; })()")
    assert pt, f'no element for {selector}'
    px = int(pt['x'] * d._DT_SCALE + d._DT_OFFSET[0])
    py = int(pt['y'] * d._DT_SCALE + d._DT_OFFSET[1])
    d.shell(f'input tap {px} {py}')
    time.sleep(0.9)


for attempt in range(2):
    state = ev("(() => { return { open: document.getElementById('settingsPanel')"
               ".classList.contains('open') }; })()")
    if state and state.get('open'):
        ev("(() => { window.Feelime.closeSettingsPanel(); return 1; })()")
        time.sleep(0.5)

open_pair()
title = ev("document.getElementById('settingsPageBar').children[1].textContent")
print('pair title:', title)
assert title in ('输入法快捷切换', 'Quick switch'), title  # 双语环境都可能出现

# real touch on ‹ back -> returns to the settings home
tap_dom('#settingsPageBar .tool')  # first .tool in the bar is the back button
home = ev("(() => ({ editor: !!document.getElementById('pairEditor'),"
          " rows: [...document.querySelectorAll('#settingsPanel .set-label')]"
          ".map(e => e.textContent) }))()")
print('after real back tap:', home)
rows = home.get('rows', []) if home else []
assert home and not home.get('editor') \
    and ('快捷切换' in rows or 'Quick switch' in rows), 'back tap failed'

# into the pair page again, real touch on × close -> panel closes, keys restored
open_pair()
tap_dom('#settingsPageBar .tool:nth-last-child(1)')  # the × is the LAST tool
closed = ev("(() => ({ open: document.getElementById('settingsPanel')"
            ".classList.contains('open'),"
            " qwerty: !document.getElementById('qwertyLayer').hidden }))()")
print('after real close tap:', closed)
assert closed and not closed.get('open') and closed.get('qwerty'), 'close tap failed'

# tools hidden while a sub-page is up (body.settings-page)
open_pair()
vis = ev("(() => { const s = getComputedStyle(document.getElementById('setupButton'));"
         " const bar = getComputedStyle(document.getElementById('settingsPageBar'));"
         " return { setup: s.display, bar: bar.display }; })()")
print('sub-page toolbar:', vis)
assert vis and vis.get('setup') == 'none' and vis.get('bar') == 'flex', vis
ev("(() => { window.Feelime.closeSettingsPanel(); return 1; })()")
print('RESULT: PASS')
