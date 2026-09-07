#!/usr/bin/env python3
"""Nine physical-gesture assertions for the Feelime keyboard on one device.

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


def tap_dom(selector, keyboard):
    """Read a DOM element's CSS center, then tap it through ADB."""
    center = d.devtools_eval(
        "(() => { const e = document.querySelector(" + repr(selector) + ");"
        " if (!e) return null; const r = e.getBoundingClientRect();"
        " return [r.left + r.width / 2, r.top + r.height / 2]; })()"
    )
    if not center:
        return False
    offset_x, offset_y = d._DT_OFFSET
    scale = keyboard["<density>"]
    d.tap(center[0] * scale + offset_x, center[1] * scale + offset_y, wait=0.2)
    return True


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

    # 8-9: J3 space hold enters native listening + overlay, release exits.
    clear(kb)
    # Cold-model warmup: the FIRST startVoice loads the streaming model
    # (102s observed on SwiftScaler/AVD cold boot) - far past any hold the
    # gesture can keep. Start one session through the mic tap path, wait for
    # listening (bounded), stop it; the recognizer stays warm and J3 keeps
    # testing the GESTURE, not the model-load latency.
    warmup_started = tap_dom("#mic", kb)
    if warmup_started:
        for _ in range(80):
            time.sleep(1.5)
            state = d.devtools_eval(
                "(() => { const overlay = document.getElementById('voiceOverlay');"
                " return overlay && overlay.classList.contains('open')"
                " ? overlay.textContent : null; })()")
            if state and any(label in state for label in ("聆听", "Listening")):
                break
            if state is None and _ > 8:
                # The overlay never opened; J3's own polling remains the
                # authoritative result for the actual space gesture.
                break
        tap_dom("#mic", kb)
    # The stop is async (stopping -> idle); a startVoice fired while still
    # stopping is swallowed and J3's overlay never opens (observed as
    # overlayOpen=False). Wait the session ALL the way out before J3.
    for _ in range(20):
        still_open = d.devtools_eval(
            "(() => { const o = document.getElementById('voiceOverlay');"
            " return !!(o && o.classList.contains('open')); })()")
        if still_open is False:
            break
        time.sleep(1.0)
    time.sleep(1.0)
    sx, sy = kb["<space>"]
    # A silent process death mid-hold (sherpa EncodeHotwords
    # exit(-1) on an unset modeling_unit; AVD additionally memory-bound)
    # leaves every DevTools eval returning None for the rest of the case -
    # surface the pid so "[None]" is self-explanatory.
    pid_before = d.shell(f"pidof {d.PKG}").strip()
    motion("DOWN", sx, sy)
    listening = None
    try:
        # Loading can legitimately take 20s+ on a cold or slow device (AVD
        # CPU translation); 'listening' breaks early on real hardware.
        # The toolbar mic button moved into the quick panel
        # (no #mic in the live DOM) - the durable listening oracle is the
        # overlay itself: open + the 聆听 state label.
        for _ in range(60):
            time.sleep(0.5)
            listening = d.devtools_eval(
                "(() => { const overlay = document.getElementById('voiceOverlay');"
                " return { overlayOpen: overlay && overlay.classList.contains('open'),"
                " text: overlay ? overlay.textContent : null }; })()"
            )
            if listening and any(label in (listening.get("text") or "")
                                 for label in ("聆听", "Listening")):
                break
        pid_after = d.shell(f"pidof {d.PKG}").strip()
        detail = repr(listening)
        if pid_after != pid_before:
            detail += f" [IME process {pid_before} -> {pid_after}: died mid-hold]"
        record(
            "J3 space hold starts listening overlay",
            bool(listening) and listening.get("overlayOpen") is True and
                any(label in (listening.get("text") or "")
                    for label in ("聆听", "Listening")),
            detail,
        )
    finally:
        motion("UP", sx, sy)

    released = None
    for _ in range(12):
        time.sleep(0.5)
        released = d.devtools_eval(
            "(() => { const overlay = document.getElementById('voiceOverlay');"
            " return { overlayOpen: overlay && overlay.classList.contains('open') }; })()"
        )
        if released and released.get("overlayOpen") is False:
            break
    record(
        "J3 space release stops listening overlay",
        bool(released) and released.get("overlayOpen") is False,
        repr(released),
    )

    failed = [name for name, ok, _ in RESULTS if not ok]
    print(f"\n== {len(RESULTS) - len(failed)}/{len(RESULTS)} passed ==")
    if failed:
        print("FAILED:", ", ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()
