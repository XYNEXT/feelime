#!/usr/bin/env python3
"""One-shot manual check: inside the phrase editor,
typing pinyin and picking a bar candidate must land the word in the editor
input (native redirect), never in the host editor. Run with
FEELIME_ADB_SERIAL=<serial> python3 scripts/verify/check_editor_candidate_pick.py
"""
import sys
import time

sys.path.insert(0, __file__.rsplit('/', 1)[0])
import device_verify as d  # noqa: E402

d.prepare()  # ime set + SetupActivity field (force-stop can drop the IME)
kb = d.fresh_kb()
assert kb, 'keyboard not up'
d.switch_mode(kb, '全拼 Pinyin')
kb = d.fresh_kb() or kb
d.devtools_eval('window.Feelime.clearEditor()')
time.sleep(0.3)

# open favorites -> add flow (the editor strip + candidate bar coexist),
# same DOM clicks the candidate-pool suite uses
ev = d.devtools_eval
assert ev("(() => { document.getElementById('favoritesButton').click(); return 1; })()") == 1
time.sleep(0.6)
assert ev("(() => { document.getElementById('panelManage').click(); return 1; })()") == 1
time.sleep(0.6)

# check the editing shape
shape = ev(
    "(() => { const e = document.getElementById('panelEditor');"
    " const b = document.getElementById('candidateBar');"
    " return { editor: !e.hidden, bar: !b.hidden,"
    " editing: document.body.classList.contains('editing'),"
    " above: e.getBoundingClientRect().bottom <= b.getBoundingClientRect().top + 1 }; })()"
)
print('shape:', shape)
assert shape and shape.get('editor') and shape.get('bar') and shape.get('above')

kb = d.fresh_kb() or kb
d.type_word(kb, 'ni', wait=0.6)
time.sleep(0.8)
state = ev(
    "(() => { const first = document.querySelector('#candidates .candidate');"
    " const r = first.getBoundingClientRect();"
    " return { text: first.textContent, x: r.left + r.width / 2, y: r.top + r.height / 2 }; })()"
)
print('first candidate:', state)
assert state and state.get('text')

# a real click on the candidate (DevTools-synthesized tap -> native click)
ok = ev(
    f"(() => {{ const el = document.elementFromPoint({state['x']}, {state['y']});"
    " el.click(); return el ? el.textContent : null; })()"
)
print('clicked:', ok)
time.sleep(1.2)
value = ev(
    "document.getElementById('panelEditorInput').value")
host = d.field_text_retry()
print(f'editor input value: {value!r}  host editor: {host!r}')
PASS = bool(value) and value not in ('', 'ni') and (host or '') == ''
print('RESULT:', 'PASS' if PASS else 'FAIL')
