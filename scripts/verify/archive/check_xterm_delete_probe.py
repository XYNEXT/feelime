#!/usr/bin/env python3
"""xterm.js (ttyd) deletion probe - DIAGNOSTIC.

Experiments (Chrome + ttyd):
- 0.8.0 (deleteSurroundingText path): committed chars NOT deleted - xterm
  ignores the rewritten textarea (no keydown, no PTY backspace).
- 0.9.0 (KEYCODE_DEL path): Chromium drops IME-injected key events entirely
  (zero keydown at the textarea), so Chrome-ttyd still cannot delete.
Both are Chromium limitations, NOT regressions between the two builds. The
real target (an xterm.js-in-WebView host whose xterm listens on
keydown, and Termux = TYPE_NULL native) routes KEYCODE_DEL differently;
Termux is probed by check_termux_delete_probe.py. Keep this
probe as a diagnostic record of the Chrome behaviour, not a release gate."""
import base64
import json
import os
import socket as _socket
import struct
import subprocess
import sys
import time

SERIAL = os.environ.get("FEELIME_ADB_SERIAL", "")
TTYD_URL = os.environ.get("FEELIME_TTYD_URL", "http://127.0.0.1:7681")
CHROME_PORT = os.environ.get("FEELIME_TTYD_CHROME_PORT", "9224")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault('FEELIME_ADB_SERIAL', SERIAL)
import device_verify as d


def adb_forward(port, socket_name):
    subprocess.run(["adb", "-s", SERIAL, "forward", f"tcp:{port}",
                    f"localabstract:{socket_name}"], capture_output=True, timeout=15)


def ws_connect(port, path):
    sock = _socket.create_connection(("127.0.0.1", int(port)), timeout=10)
    key = base64.b64encode(os.urandom(16)).decode()
    sock.sendall(
        (f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
         "Upgrade: websocket\r\nConnection: Upgrade\r\n"
         f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode()
    )
    while b"\r\n\r\n" not in sock.recv(4096):
        pass
    return sock


def ws_eval(sock, expr, mid=[100]):
    mid[0] += 1
    payload = json.dumps({"id": mid[0], "method": "Runtime.evaluate",
                          "params": {"expression": expr, "returnByValue": True}}).encode()
    mask = os.urandom(4)
    frame = bytearray([0x81])
    n = len(payload)
    if n < 126:
        frame.append(0x80 | n)
    elif n < 65536:
        frame.append(0x80 | 126)
        frame += struct.pack(">H", n)
    else:
        frame.append(0x80 | 127)
        frame += struct.pack(">Q", n)
    frame += mask
    frame += bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    sock.sendall(bytes(frame))
    buffer = b""
    deadline = time.time() + 8
    while time.time() < deadline:
        chunk = sock.recv(65536)
        if not chunk:
            break
        buffer += chunk
        while len(buffer) >= 2:
            size = buffer[1] & 0x7F
            offset = 2
            if size == 126:
                if len(buffer) < 4:
                    break
                size = struct.unpack(">H", buffer[2:4])[0]
                offset = 4
            elif size == 127:
                if len(buffer) < 10:
                    break
                size = struct.unpack(">Q", buffer[2:10])[0]
                offset = 10
            if len(buffer) < offset + size:
                break
            data = buffer[offset:offset + size]
            buffer = buffer[offset + size:]
            try:
                msg = json.loads(data)
            except ValueError:
                continue
            if msg.get("id") == mid[0]:
                return msg.get("result", {}).get("result", {}).get("value")
    return None


def main():
    # Bring the IME process up and bind it cleanly BEFORE anything else -
    # without prepare() the first install-after-restart can leave the IME's
    # InputConnection pointed at a dead editor and every keystroke vanishes.
    d.prepare()
    adb_forward(CHROME_PORT, "chrome_devtools_remote")
    listing = subprocess.run(["curl", "-s", "-m", "6",
                              f"http://127.0.0.1:{CHROME_PORT}/json/list"],
                             capture_output=True, timeout=10).stdout.decode()
    pages = json.loads(listing or "[]")
    ttyd = [p for p in pages if TTYD_URL in p.get("url", "") and p.get("type") == "page"]
    if not ttyd:
        print("FAIL no ttyd tab found; tabs:", [p.get("url") for p in pages][:6])
        return 1
    chrome = ws_connect(CHROME_PORT, ttyd[0]["webSocketDebuggerUrl"].split(f"{CHROME_PORT}")[1])
    print("chrome tab connected:", ttyd[0]["url"][:60])

    # sanity: xterm helper textarea exists (this really is xterm.js)
    info = ws_eval(chrome, "(() => ({ ta: !!document.querySelector('.xterm-helper-textarea'),"
                   " term: typeof term }))()")
    print("xterm check:", info)
    if not info or not info.get("ta"):
        print("FAIL not an xterm.js page")
        return 1
    screen_js = ("(() => Array.from({length: term.rows}, (_, y) =>"
                 " term.buffer.active.getLine(y).translateToString(true))"
                 ".filter(s => s.trim()).join(' | ').slice(-160))()")
    # instrument: count Backspace keydowns reaching the page
    ws_eval(chrome, "window.__bk = 0;"
            "document.addEventListener('keydown', e => { if (e.key === 'Backspace') window.__bk++; }, true);"
            "1")

    # focus the terminal: bring chrome to front FIRST (an earlier suite may
    # have left another app foregrounded - the tap then lands there and the
    # whole probe silently types into the wrong editor), then tap the
    # terminal area so the keyboard rises.
    d.shell(f"am start -a android.intent.action.VIEW -d 'http://{TTYD_URL}'")
    time.sleep(4)
    d.shell("input tap 540 1000")
    time.sleep(2.5)
    kb = d.fresh_kb(refocus=False)
    if not kb:
        d.shell("input tap 540 700")
        time.sleep(2)
        kb = d.fresh_kb(refocus=False)
    if not kb:
        print("FAIL feelime keyboard not up over chrome")
        return 1
    d.switch_mode(kb, "英文 Direct")
    kb = d.fresh_kb(refocus=False) or kb
    focus = ws_eval(chrome, "document.activeElement && document.activeElement.className")
    print("focused element:", focus)
    # NOTE: terminal command typing is Direct in practice (double-pinyin
    # compositions are for words, and their backspace is consumed in-engine
    # by design). #2 is about COMMITTED characters deleting, which is the
    # Direct path here and the only path xterm-host users type commands with.

    # type abc (enters the double-pinyin composition), then Enter commits the
    # raw letters to the terminal - bash echoes them on the next prompt line.
    rows_before = None
    for attempt in range(2):
        for ch in "abc":
            d.press(kb, ch, 0.18)
        time.sleep(0.4)
        pe = d.devtools_preedit()
        print(f'try{attempt}: after abc preedit={pe!r} field={d.field_text_retry()!r} '
              f'chip={d.keyboard_chip()!r}')
        d.press(kb, "<enter>", 0.3)
        time.sleep(1.5)
        rows_before = ws_eval(chrome, screen_js)
        bk_before = ws_eval(chrome, "window.__bk")
        print(f"try{attempt}: screen={repr((rows_before or '')[-80:])} bk={bk_before}")
        if rows_before and "abc" in rows_before:
            break
        # cleanup: clear whatever landed and retry
        for _ in range(5):
            d.press(kb, "<backspace>", 0.15)
        time.sleep(0.8)
    bk_before = ws_eval(chrome, "window.__bk")
    print("before delete:", repr((rows_before or '')[-120:]), "keydown-bk:", bk_before)
    if not rows_before or "abc" not in rows_before:
        print("WARN terminal did not echo abc - checking focus/PTY anyway")

    # three backspaces
    for _ in range(3):
        d.press(kb, "<backspace>", 0.25)
    time.sleep(1.5)
    rows_after = ws_eval(chrome, screen_js)
    bk_after = ws_eval(chrome, "window.__bk")
    print("after delete: ", repr((rows_after or '')[-120:]), "keydown-bk:", bk_after)

    deleted = (rows_before is not None and rows_after is not None
               and ("abc" in rows_before) and ("abc" not in rows_after))
    got_key = (bk_after or 0) - (bk_before or 0) >= 3
    ok = got_key and deleted
    print(f"keydown path reached xterm: {got_key}; screen lost 'abc': {deleted}")
    print("PASS xterm deletion works end to end" if ok
          else "FAIL xterm did NOT delete committed characters")
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
