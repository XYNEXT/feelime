#!/usr/bin/env python3
""" Termux deletion probe (TYPE_NULL / native terminal)."""
import os, sys, time, subprocess
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("FEELIME_ADB_SERIAL", "")
import device_verify as d

OUT = os.environ.get('FEELIME_PROBE_OUT', '/tmp/feelime-probes')


def shot(name):
    d.shell("screencap -p /sdcard/t11.png")
    subprocess.run(["adb", "-s", os.environ['FEELIME_ADB_SERIAL'],
                    "pull", "/sdcard/t11.png", f"{OUT}/{name}.png"], capture_output=True)


d.prepare()
d.shell("am start -n com.termux/.app.TermuxActivity")
time.sleep(3)
# short tap to focus the terminal (long tap opens the menu - avoid it)
d.shell("input tap 540 900")
time.sleep(2)
kb = d.fresh_kb(refocus=False)
if not kb:
    print('FAIL keyboard not up')
    raise SystemExit(1)
d.switch_mode(kb, "英文 Direct")
kb = d.fresh_kb(refocus=False) or kb
print('chip:', d.keyboard_chip())

for ch in "hi":
    d.press(kb, ch, 0.2)
time.sleep(0.6)
shot('t11-with-hi')

d.press(kb, "<backspace>", 0.3)
time.sleep(0.6)
shot('t11-after-del')
d.press(kb, "<backspace>", 0.3)
time.sleep(0.6)
shot('t11-after-del2')
print('done - compare t11-with-hi vs t11-after-del visually')
