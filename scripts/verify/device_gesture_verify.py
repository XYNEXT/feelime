#!/usr/bin/env python3
"""Pure physical-gesture assertions for the Feelime keyboard on one device.

Voice hold (J3) lives in device_voice_hold_verify.py: the streaming model's
cold load dominated this suite, and a failed voice case used to leak the
overlay into every following suite.

Requires FEELIME_ADB_SERIAL. Reuses device_verify's DevTools and native
EditText oracles, but every gesture itself is injected as a real touchscreen
motion event rather than invoked through JavaScript.
"""
import sys
import time

import device_verify as d


RESULTS = []


def record(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail else ""), flush=True)


def motion(action, x, y):
    """Inject one real touchscreen event at physical screen coordinates."""
    if action not in {"DOWN", "MOVE", "UP", "CANCEL"}:
        raise ValueError(f"unsupported motion action: {action!r}")
    d.shell(f"input touchscreen motionevent {action} {int(x)} {int(y)}")
    # Let the WebView dispatch the event before the next assertion or event.
    time.sleep(0.06)


def swipe(x1, y1, x2, y2, dur_ms=160):
    """Inject one real touchscreen swipe at physical screen coordinates."""
    d.shell(
        f"input touchscreen swipe {int(x1)} {int(y1)} "
        f"{int(x2)} {int(y2)} {int(dur_ms)}"
    )
    time.sleep(0.06)


def clear(kb):
    d.clear_field(kb)
    time.sleep(0.3)


def main():
    d.prepare()
    kb = d.fresh_kb()
    if not kb:
        raise SystemExit("keyboard geometry unavailable")
    d.reset_shift(kb)
    d.switch_mode(kb, "英文 Direct")
    kb = d.fresh_kb() or kb

    # 1: backspace hold repeats until all committed text is gone.
    clear(kb)
    d.type_word(kb, "abcdef", wait=0.12)
    bx, by = kb["<backspace>"]
    motion("DOWN", bx, by)
    time.sleep(1.0)
    motion("UP", bx, by)
    time.sleep(0.5)
    text = d.field_text_retry()
    record("backspace long-hold repeats", text == "", repr(text))

    # 2-3: vertical flicks produce e's alternate and uppercase forms.
    ex, ey = kb["e"]
    clear(kb)
    swipe(ex, ey, ex, ey - 160)
    time.sleep(0.5)
    text = d.field_text_retry()
    record("e flick-up emits alternate", text == "3", repr(text))

    clear(kb)
    swipe(ex, ey, ex, ey + 160)
    time.sleep(0.5)
    text = d.field_text_retry()
    record("e flick-down emits uppercase", text == "E", repr(text))

    # 4-5: popup structure and its default uppercase selection.
    clear(kb)
    motion("DOWN", ex, ey)
    try:
        time.sleep(0.55)
        popup = d.devtools_eval(
            "(() => ({ open: document.getElementById('keyPopup').classList.contains('open'),"
            " items: [...document.querySelectorAll('#keyPopup .kp-item')].map(e => e.textContent),"
            " selected: document.querySelector('#keyPopup .kp-item.sel')?.textContent || '' }))()"
        ) or {}
        record(
            "e long-hold popup candidates",
            popup.get("open") is True and popup.get("items") == ["3", "E", "e"],
            repr(popup),
        )
    finally:
        motion("UP", ex, ey)
    time.sleep(0.5)
    text = d.field_text_retry()
    record(
        "popup default selects uppercase",
        popup.get("selected") == "E" and text == "E",
        f"selected={popup.get('selected')!r} text={text!r}",
    )

    # 6: drag the same popup to its first item and release.
    clear(kb)
    motion("DOWN", ex, ey)
    try:
        time.sleep(0.55)
        first = d.devtools_eval(
            "(() => { const e = document.querySelector('#keyPopup .kp-item');"
            " if (!e) return null; const r = e.getBoundingClientRect();"
            " return [r.left + r.width / 2, r.top + r.height / 2, e.textContent]; })()"
        )
        if first:
            offset_x, offset_y = d._DT_OFFSET
            scale = kb["<density>"]
            target_x = first[0] * scale + offset_x
            target_y = first[1] * scale + offset_y
            motion("MOVE", target_x, target_y)
            time.sleep(0.15)
            motion("UP", target_x, target_y)
        else:
            motion("CANCEL", ex, ey)
    except BaseException:
        motion("CANCEL", ex, ey)
        raise
    time.sleep(0.5)
    text = d.field_text_retry()
    record("popup drag selects first item", bool(first) and first[2] == "3" and text == "3", repr(text))

    # 6b: dragging far from every popup cell cancels the pick - the layer
    # fades (transform/opacity) and the release commits nothing.
    clear(kb)
    motion("DOWN", ex, ey)
    try:
        time.sleep(0.55)
        far = d.devtools_eval(
            "(() => ({ w: window.innerWidth, open:"
            " document.getElementById('keyPopup').classList.contains('open') }))()"
        )
        if far and far.get("open"):
            # ~500 device px to the right (~180 CSS px at density 2.75):
            # outside every cell's 60px CSS reach and past the 170px
            # fully-gone radius.
            motion("MOVE", int(ex + 500), int(ey - 30))
            time.sleep(0.2)
            faded = d.devtools_eval(
                "(() => { const inner = document.getElementById('keyPopupInner');"
                " return { transform: inner.style.transform, opacity: inner.style.opacity,"
                " sel: document.querySelector('#keyPopup .kp-item.sel')?.textContent || null }; })()"
            )
            motion("UP", int(ex + 500), int(ey - 30))
        else:
            faded = None
            motion("CANCEL", ex, ey)
    except BaseException:
        motion("CANCEL", ex, ey)
        raise
    time.sleep(0.5)
    text = d.field_text_retry()
    record(
        "B9c popup drag-away fades and commits nothing",
        bool(faded) and faded.get("transform") != "" and faded.get("sel") is None and text == "",
        f"faded={faded} text={text!r}",
    )

    # 7: a left swipe scrubs the caret continuously , landing at the
    # line start; x then inserts there. The text landing in THIS fixture also
    # proves focus never left the editor - the old DPAD keyevents moved focus
    # to neighbouring focusables in chat apps.
    clear(kb)
    d.type_word(kb, "abc", wait=0.15)
    gx, gy = kb["g"]
    # Engage rewinds the anchor one unit behind the finger, so
    # the recognition slop (~38 CSS px) plus that rewind never count as
    # steps. A real ADB swipe emits the intermediate MOVE events that the
    # scrubber consumes; 260 physical px crosses slop plus three 12px units
    # with room to spare, landing the caret at the line start.
    swipe(gx, gy, gx - 260, gy, dur_ms=200)
    time.sleep(0.3)
    d.press(kb, "x", 0.4)
    text = d.field_text_retry()
    record("R303 scrub moves caret to start for insertion", text == "xabc", repr(text))

    failed = [name for name, ok, _ in RESULTS if not ok]
    print(f"\n== {len(RESULTS) - len(failed)}/{len(RESULTS)} passed ==")
    if failed:
        print("FAILED:", ", ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()
