#!/usr/bin/env python3
""" Probe: Termux backspace with user's real path (double-pinyin
saved mode, mode switch inside Termux, real key path)."""
import os, sys, time, subprocess
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("FEELIME_ADB_SERIAL", "")
import device_verify as d

def shot(name):
    d.shell("screencap -p /sdcard/t10.png")
    subprocess.run(["adb", "-s", os.environ['FEELIME_ADB_SERIAL'],
                    "pull", "/sdcard/t10.png", f"{os.environ.get('FEELIME_PROBE_OUT', '/tmp/feelime-probes')}/{name}.png"],
                   capture_output=True)

# saved user mode -> double pinyin BEFORE entering Termux
d.prepare()
kb = d.fresh_kb()
d.switch_mode(kb, "双拼")
print('chip now:', d.keyboard_chip())

# enter Termux
d.shell("am start -n com.termux/.app.TermuxActivity")
time.sleep(3)
d.shell("input tap 540 700")
time.sleep(2)
print('inputType:', d.shell(
    "dumpsys input_method | grep -E 'mCurMethodId|inputType' | head -3").strip())
kb = d.fresh_kb(refocus=False)
print('chip in termux:', d.keyboard_chip())
if not kb:
    print('!! keyboard geometry unavailable'); raise SystemExit(1)

# type abc then backspace once - expect 'ab'
for ch in "abc":
    d.press(kb, ch, 0.15)
time.sleep(0.5)
shot('t10-bp-before')
d.press(kb, "<backspace>", 0.3)
time.sleep(0.5)
shot('t10-bp-after1')
d.press(kb, "<backspace>", 0.3)
time.sleep(0.3)
d.press(kb, "<backspace>", 0.3)
time.sleep(0.5)
shot('t10-bp-after3')

# now try switching to pinyin inside Termux (selectMode should be blocked,
# keyboard stays Direct) and backspace again
d.devtools_click_mode("全拼 Pinyin")
time.sleep(1.5)
print('chip after switch attempt:', d.keyboard_chip())
kb = d.fresh_kb(refocus=False) or kb
for ch in "xyz":
    d.press(kb, ch, 0.15)
d.press(kb, "<backspace>", 0.3)
time.sleep(0.5)
shot('t10-after-switch-bs')
for _ in range(8):
    d.press(kb, "<backspace>", 0.12)
print('logcat tail:')
print(d.shell("logcat -d -s FeelimePanel:* | tail -12"))
