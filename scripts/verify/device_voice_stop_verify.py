#!/usr/bin/env python3
""" Production voice stop/cancel gate, using real editor and touches.

Requires FEELIME_ADB_SERIAL, FEELIME_VERIFY_APK, FEELIME_ASR_FIXTURE and
FEELIME_ASR_INJECTOR, as documented by device_asr_production_verify.py.
The injector must feed the target microphone; this suite never sends fake
ASR callbacks. The emulator gRPC injector is one supported implementation.
An unavailable injector is a failed prerequisite, not a transcript pass.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import time
import wave

import device_asr_production_verify as asr
import fv_common as shared
import device_verify as d


RESULTS = []


def record(name, ok, detail=""):
    RESULTS.append(bool(ok))
    print(("PASS " if ok else "FAIL ") + name +
          (f" [{detail}]" if detail else ""), flush=True)
    return bool(ok)


def state():
    return d.devtools_eval(
        "(() => ({open:document.getElementById('voiceOverlay').classList.contains('open'),"
        "hint:document.getElementById('voiceHint').textContent,"
        "status:document.getElementById('voiceStatus').textContent}))()") or {}


def tap(selector):
    point = shared.keyboard_point(selector)
    if not point:
        raise RuntimeError(f"missing visible control: {selector}")
    d.tap(*point, wait=0.25)


def seed(text, selected=False):
    d.app_hard_reset()
    d.prepare()
    if not shared.switch_mode_real("英文 Direct"):
        raise RuntimeError("cannot select Direct mode")
    d.shell("input keycombination 113 29")
    d.shell("input keyevent 67")
    d.shell("input text " + text)
    if d.field_text_retry() != text:
        raise RuntimeError("native editor seed mismatch")
    if selected:
        d.shell("input keycombination 113 29")
    d.shell("logcat -c")


def inject(command):
    ok, detail = asr.inject_audio(command, asr.DEFAULT_INJECT_TIMEOUT)
    if not record("voice microphone audio delivered", ok, detail):
        raise RuntimeError("audio injection failed")
    time.sleep(1)


def wait_idle():
    ok, last = asr.wait_voice_idle()
    record("voice returns idle", ok, repr(last))
    if not ok:
        raise RuntimeError("voice did not stop")


def cancel_case(command, selected=False):
    original = "ORIGINAL" if selected else "PREFIX"
    seed(original, selected)
    asr.start_voice(asr.DEFAULT_VOICE_TIMEOUT)
    record("toolbar voice says tap to finish",
           state().get("hint") in ("点击任意位置结束", "Tap anywhere to finish."), str(state()))
    if selected:
        inject(command)
    else:
        # Two short real utterances separated by silence exercise endpoint
        # ownership without making this cancellation test a long-recording
        # speed benchmark on translated ARM code in an x86 emulator.
        with wave.open(os.environ["FEELIME_ASR_FIXTURE"], "rb") as source:
            params = source.getparams()
            utterance = source.readframes(2 * params.framerate)
        silence = bytes(2 * params.framerate * params.nchannels * params.sampwidth)
        temp_root = Path(os.environ.get("FEELIME_VERIFY_TMP_DIR", Path.home() / "tmp"))
        temp_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="feelime-voice-segments-", dir=temp_root) as tmp:
            segmented = Path(tmp) / "two-short-utterances.wav"
            with wave.open(str(segmented), "wb") as target:
                target.setparams(params)
                target.writeframes((utterance + silence) * 2)
            inject(asr.build_injector_command(os.environ["FEELIME_ASR_INJECTOR"], segmented))
        deadline = time.monotonic() + 20
        count = 0
        while time.monotonic() < deadline:
            logs = d.shell("logcat -d -s FeelimeAsr:I '*:S'")
            count = sum("endpoint stream=[" in line and "final=[]" not in line
                        for line in logs.splitlines())
            if count >= 2:
                break
            time.sleep(1)
        record("cancel covers multiple recognized endpoints", count >= 2, f"endpoints={count}")
    interim = d.field_text_retry() or ""
    record("voice temporary text reached real editor", interim != original and bool(interim), repr(interim))
    tap("#voiceClose")
    immediate = d.field_text_retry()
    record("cancel restores selected text" if selected else "cancel removes entire recording",
           immediate == original, repr(immediate))
    wait_idle()
    time.sleep(3)
    final = d.field_text_retry()
    record("cancelled recording cannot return after completion", final == original, repr(final))


def inside_stop_case(command):
    seed("PREFIX")
    asr.start_voice(asr.DEFAULT_VOICE_TIMEOUT)
    inject(command)
    tap("#voiceStatus")
    wait_idle()
    text = d.field_text_retry() or ""
    record("tapping inside card commits recording",
           text.startswith("PREFIX") and "monday" in text.lower() and "星期三" in text, repr(text))


def hold_case(command):
    seed("PREFIX")
    point = shared.keyboard_point("#spaceKey")
    if not point:
        raise RuntimeError("space key unavailable")
    x, y = point
    hold = subprocess.Popen(["adb", "-s", d.SERIAL, "shell", "input", "swipe",
                             str(x), str(y), str(x), str(y), "35000"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            current = state()
            if current.get("open") and any(word in current.get("status", "")
                                           for word in ("聆听", "Listening")):
                break
            time.sleep(0.4)
        else:
            raise RuntimeError("space hold never reached listening")
        record("space hold says release to finish",
               current.get("hint") in ("松手结束", "Release to finish."), str(current))
        inject(command)
        hold.communicate(timeout=45)
        if hold.returncode != 0:
            raise RuntimeError("physical long press failed")
        wait_idle()
        text = d.field_text_retry() or ""
        record("releasing space commits recording",
               text.startswith("PREFIX") and "monday" in text.lower() and "星期三" in text, repr(text))
    finally:
        if hold.poll() is None:
            hold.terminate()
            hold.communicate(timeout=5)
            d.shell(f"input motionevent CANCEL {x} {y}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", choices=("inside", "cancel", "selection", "hold"))
    args = parser.parse_args()
    apk = Path(os.environ["FEELIME_VERIFY_APK"])
    audio = Path(os.environ["FEELIME_ASR_FIXTURE"])
    matches, detail = asr.installed_apk_matches(apk)
    if not matches:
        raise RuntimeError("installed APK mismatch: " + detail)
    asr.validate_audio(audio, False)
    command = asr.build_injector_command(os.environ["FEELIME_ASR_INJECTOR"], audio)
    asr.validate_injector_command(command)
    asr.ensure_mic_permission()
    for name, action in [
        ("inside", lambda: inside_stop_case(command)),
        ("cancel", lambda: cancel_case(command)),
        ("selection", lambda: cancel_case(command, selected=True)),
        ("hold", lambda: hold_case(command)),
    ]:
        if args.case and args.case != name:
            continue
        try:
            action()
        except Exception as error:
            record("voice " + name, False, str(error) + " state=" + repr(state()))
            d.shell("am force-stop " + d.PKG)
    print(f"voice: {sum(RESULTS)}/{len(RESULTS)} passed", flush=True)
    return 0 if RESULTS and all(RESULTS) else 1


if __name__ == "__main__":
    raise SystemExit(main())
