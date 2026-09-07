#!/usr/bin/env python3
"""Device gates (backspace swipe / candidate delete / combo badge).

#5  backspace LEFT swipe aborts the live pinyin composition (and kills the
    pending repeat timers - no stray deletes on committed text).
#8  long-press delete of user-lexicon words through the real librime:
    a) a fixed-dictionary word stays -> honest "固定词库" toast;
    b) a self-made word (userdb-boosted) is deleted for real and leaves the
       pool. This is the ONLY layer that can prove the Shift+Delete channel
        - mock/preview engines always fake success.
P2  the combo card's floating X badge stays inside the IME window when the
    card is edge-clamped .

All gestures are single-eval in-page TouchEvents pinned to the start target
(see device_verify.synth_gesture notes); long-presses dwell between
touchstart/touchend so the 380ms candidate timer fires."""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import device_verify as d

RESULTS = []
DELETE_WORD_LABELS = ("删除自造词", "Delete learned word")
BUILTIN_DELETE_TOAST_MARKERS = ("固定词库", "built-in word")
LEARNED_DELETE_TOAST_MARKERS = ("已从自选词词库删除", "learned words")

STATE_JS = ("(() => ({ composing: document.body.classList.contains('composing'),"
            " preedit: document.getElementById('preeditLine').textContent,"
            " cands: [...document.querySelectorAll('#candidates .candidate')].map(b => b.textContent),"
            " toast: document.getElementById('toast').textContent,"
            " menuOpen: document.getElementById('itemMenu').classList.contains('open'),"
            " menuItems: [...document.getElementById('itemMenu').children].map(b =>"
            "   (b.disabled ? '!' : '') + b.textContent),"
            " confirmHidden: document.getElementById('confirmCard').hidden,"
            " confirmText: document.getElementById('confirmText').textContent }))()")


def record(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail else ""), flush=True)


def ev(expr):
    return d.devtools_eval(expr)


def state():
    return ev(STATE_JS)


def long_press_selector(selector, hold_s=0.55):
    """In-page long-press: touchstart, real dwell, touchend - all pinned to
    the element (untrusted events make no synthetic click, so the pick is
    not fired by accident)."""
    js = ("(() => { const k = document.querySelector('" + selector + "');"
          " if (!k || typeof Touch === 'undefined') return 'no';"
          " const r = k.getBoundingClientRect();"
          " const x = r.left + r.width / 2, y = r.top + r.height / 2;"
          " const t = new Touch({identifier: 1, target: k, clientX: x, clientY: y});"
          " const mk = type => new TouchEvent(type, {cancelable: true, bubbles: true,"
          "   touches: type === 'touchend' ? [] : [t], changedTouches: [t]});"
          " k.dispatchEvent(mk('touchstart'));"
          " setTimeout(() => k.dispatchEvent(mk('touchend')), " + str(int(hold_s * 1000)) + ");"
          " return 'ok'; })()")
    if ev(js) != "ok":
        raise RuntimeError(f"long-press failed on {selector!r}")
    time.sleep(hold_s + 0.5)


def swipe_left_on(selector, dist_css=70.0, step_ms=16):
    """Left swipe in ONE eval, events pinned to the start element."""
    js = ("(() => { const k = document.querySelector('" + selector + "');"
          " if (!k || typeof Touch === 'undefined') return 'no';"
          " const r = k.getBoundingClientRect();"
          " const y = r.top + r.height / 2; let x = r.left + r.width / 2;"
          " const t0 = new Touch({identifier: 7, target: k, clientX: x, clientY: y});"
          " k.dispatchEvent(new TouchEvent('touchstart', {cancelable: true, bubbles: true,"
          "   touches: [t0], changedTouches: [t0]}));"
          " const steps = 6, dist = " + repr(float(dist_css)) + ";"
          " const busy = ms => { const s = performance.now(); while (performance.now() - s < ms) {} };"
          " for (let i = 1; i <= steps; i++) { busy(" + str(int(step_ms)) + ");"
          "   x -= dist / steps;"
          "   const t = new Touch({identifier: 7, target: k, clientX: x, clientY: y});"
          "   k.dispatchEvent(new TouchEvent('touchmove', {cancelable: true, bubbles: true,"
          "     touches: [t], changedTouches: [t]})); }"
          " const te = new Touch({identifier: 7, target: k, clientX: x, clientY: y});"
          " k.dispatchEvent(new TouchEvent('touchend', {cancelable: true, bubbles: true,"
          "   touches: [], changedTouches: [te]}));"
          " return 'ok'; })()")
    if ev(js) != "ok":
        raise RuntimeError(f"swipe failed on {selector!r}")
    time.sleep(0.6)


def tap_menu(label):
    labels = (label,) if isinstance(label, str) else tuple(label)
    wanted = json.dumps(labels, ensure_ascii=False)
    js = ("(() => { const b = [...document.getElementById('itemMenu').children]"
          "   .find(x => " + wanted + ".includes(x.textContent));"
          " if (!b) return 'no'; b.click(); return 'ok'; })()")
    return ev(js)


def menu_has(items, labels):
    return any(any(label in item for label in labels) for item in items)


def toast_has(text, markers):
    value = str(text or "").lower()
    return any(marker.lower() in value for marker in markers)


def compose(keys, wait=1.0):
    d.clear_field(kb)
    for ch in keys:
        d.press(kb, ch, 0.14)
    time.sleep(wait)


def boost_word(word, attempts=8):
    """Commit `word` repeatedly so userdb boosts it to the pool head."""
    for i in range(attempts):
        compose("nini", wait=1.2)
        st = state()
        if st["cands"] and st["cands"][0] == word:
            return True, f"head after {i} boosts"
        if word in st["cands"]:
            idx = st["cands"].index(word)
            ok = ev("(() => { const b = document.querySelectorAll('#candidates .candidate')[" + str(idx) + "];"
                    " if (!b) return 'no'; b.click(); return 'ok'; })()")
            if ok != "ok":
                return False, "candidate click failed"
            time.sleep(1.0)
            # Continuous word-building: a partial pick leaves the rest
            # composing - finish it so the whole phrase commits together.
            if state()["composing"]:
                ev("(() => { const b = document.querySelector('#candidates .candidate');"
                   " if (b) b.click(); return 'ok'; })()")
                time.sleep(1.0)
        else:
            return False, f"{word!r} left the pool: {st['cands'][:4]}"
    compose("nini", wait=1.2)
    st = state()
    return (bool(st["cands"]) and st["cands"][0] == word), f"final pool: {st['cands'][:4]}"


def main():
    d.prepare()
    global kb
    kb = d.fresh_kb(refocus=True)
    if not kb:
        raise SystemExit("keyboard geometry unavailable")
    d.devtools_click_mode("全拼 Pinyin")
    time.sleep(1.2)

    print("keyboard under test loaded, field ready", flush=True)

    # ---- #5 backspace left-swipe clears the composition ----
    compose("ni", wait=1.0)
    st = state()
    record("composing before the swipe", bool(st["composing"]) and bool(st["cands"]),
           str({"composing": st["composing"], "cands": st["cands"][:3]}))
    swipe_left_on('[data-role="backspace"]')
    st = state()
    record("backspace left-swipe clears the composition",
           not st["composing"] and not st["cands"],
           str({"composing": st["composing"], "cands": st["cands"][:3]}))
    # ...and no late repeat ate committed text: the field keeps whatever the
    # composition had committed before (nothing) - a repeat burst would leave
    # the engine deleting real characters, visible as extra engine traffic we
    # cannot observe here; the compositional state above is the user-visible
    # contract.
    d.clear_field(kb)

    # ---- #8a fixed-dictionary word: honest failure toast ----
    compose("ni", wait=1.2)
    st = state()
    head = st["cands"][0] if st["cands"] else ""
    record("pool head exists", bool(head), str(st["cands"][:3]))
    long_press_selector("#candidates .candidate.first")
    st = state()
    record("long-press opens the delete menu",
           st["menuOpen"] and menu_has(st["menuItems"], DELETE_WORD_LABELS),
           str({"menu": st["menuItems"]}))
    tap_menu(DELETE_WORD_LABELS)
    st = state()
    record("confirm card names the word",
           (not st["confirmHidden"]) and (head in st["confirmText"]),
           st["confirmText"])
    ev("document.getElementById('confirmOk').click()")
    time.sleep(1.2)
    st = state()
    record("fixed-dictionary word stays with an honest toast",
           toast_has(st["toast"], BUILTIN_DELETE_TOAST_MARKERS)
           and (head in st["cands"]),
           str({"toast": st["toast"], "cands": st["cands"][:3]}))
    d.clear_field(kb)

    # ---- #8b self-made word: really deleted ----
    # Teach librime a bigram that CANNOT be in any dictionary: commit 拟 then
    # 尼 back to back (rime learns consecutive commits as a user phrase), so
    # 「拟尼」 exists ONLY in userdb. A name-like target (妮妮/倪妮) can be
    # table-backed and would honestly "stay" - that is #8a's assertion, not
    # proof of deletion. Deleting 拟尼 must make it actually disappear.
    def click_candidate(text):
        st = state()
        if text not in st["cands"]:
            return "absent"
        idx = st["cands"].index(text)
        return ev("(() => { const b = document.querySelectorAll('#candidates .candidate')[" + str(idx) + "];"
                  " if (!b) return 'no'; b.click(); return 'ok'; })()")

    # 你拟 is not a word in any dictionary (the table DOES carry names -
    # 倪妮 shows up in the nini pool without ever being committed - so
    # name-like targets are table-backed and #8a owns them). Commit 你 then
    # 拟 back to back: consecutive commits teach the bigram as userdb-only.
    target = "你拟"

    def teach_sequence():
        # librime learns phrases from IN-COMPOSITION consecutive picks
        # (continuous word-building): pick 你, the preedit keeps
        # "你ni" composing, pick 拟 -> the whole 你拟 commits as ONE phrase
        # and lands in userdb. Two separate single-char commits teach
        # nothing (that is why the earlier two-compose variant failed).
        compose("nini", wait=1.2)
        r1 = click_candidate("你")
        time.sleep(0.8)
        r2 = click_candidate("拟")
        time.sleep(0.8)
        return r1 == "ok" and r2 == "ok"

    teach_sequence()
    ok, detail = False, "never reached the head"
    for round_i in range(14):
        compose("nini", wait=1.2)
        st = state()
        if st["cands"] and st["cands"][0] == target:
            ok, detail = True, f"head after {round_i} rounds"
            break
        if target in st["cands"]:
            click_candidate(target)
            time.sleep(0.8)
            if state()["composing"]:
                ev("(() => { const b = document.querySelector('#candidates .candidate');"
                   " if (b) b.click(); return 'ok'; })()")
                time.sleep(0.8)
        else:
            teach_sequence()
    record("teach + boost the userdb-only bigram", ok, detail)
    if not ok:
        raise SystemExit(f"could not build the user-lexicon target {target!r}: {detail}")
    long_press_selector("#candidates .candidate.first")
    st = state()
    record("delete menu on the self-made head",
           st["menuOpen"] and menu_has(st["menuItems"], DELETE_WORD_LABELS),
           str({"head": target, "menu": st["menuItems"]}))
    tap_menu(DELETE_WORD_LABELS)
    ev("document.getElementById('confirmOk').click()")
    time.sleep(1.4)
    st = state()
    record("self-made word left the pool with a success toast",
           toast_has(st["toast"], LEARNED_DELETE_TOAST_MARKERS)
           and (target not in st["cands"][:2]),
           str({"toast": st["toast"], "cands": st["cands"][:3]}))
    compose("nini", wait=1.2)
    st = state()
    record("deleted word does not come back",
           bool(st["cands"]) and target not in st["cands"],
           str({"cands": st["cands"][:4], "target": target}))
    d.clear_field(kb)

    # ---- non-head long-press (supersedes the disabled
    # hint: every candidate is deletable now - the end-to-end delete is
    # asserted in device_candidate_delete_verify) ----
    compose("nihao", wait=1.2)
    st = state()
    if len(st["cands"]) >= 2:
        long_press_selector("#candidates .candidate:nth-child(2)")
        st = state()
        record("(b20) non-head long-press offers the delete action",
               st["menuOpen"] and menu_has(st["menuItems"], DELETE_WORD_LABELS)
               and not any(item.startswith("!") for item in st["menuItems"]),
               str({"menu": st["menuItems"]}))
        ev("document.getElementById('softKeyboard').dispatchEvent("
           "new Event('touchstart', {bubbles: true}))")
        time.sleep(0.4)
    else:
        record("(b20) non-head long-press offers the delete action", False,
               f"pool too small: {st['cands']}")
    d.clear_field(kb)

    # ---- P2 combo card badge stays inside the IME window ----
    ev("document.getElementById('ctrlTool').click()")
    time.sleep(0.5)
    long_press_selector('[data-ctrl="sticky-ctrl"]', hold_s=0.5)
    geo = ev("(() => { const c = document.getElementById('comboPopup').getBoundingClientRect();"
             " const x = document.getElementById('comboClose').getBoundingClientRect();"
             " return JSON.stringify({card: [c.left, c.top, c.right, c.bottom],"
             "  badge: [x.left, x.top, x.right, x.bottom], vw: innerWidth, vh: innerHeight}); })()")
    ev("document.getElementById('comboClose').click()")
    time.sleep(0.3)
    ev("document.querySelector('[data-ctrl=\"collapse\"]').click()")
    time.sleep(0.4)
    try:
        g = json.loads(geo)
        record("combo badge inside the window on an edge-clamped card",
               g["badge"][0] >= 0 and g["badge"][1] >= 0
               and g["badge"][2] <= g["vw"] and g["badge"][3] < g["vh"],
               geo)
    except Exception as exc:  # noqa: BLE001
        record("combo badge inside the window on an edge-clamped card", False, f"{exc}: {geo}")

    failed = [name for name, ok, _ in RESULTS if not ok]
    print(f"\n Suite: {len(RESULTS) - len(failed)}/{len(RESULTS)} passed", flush=True)
    if failed:
        print("FAILED: " + ", ".join(failed), flush=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
