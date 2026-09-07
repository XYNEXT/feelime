#!/usr/bin/env python3
"""Play a local WAV through a service-only Android audio probe.

This is the ``FEELIME_ASR_INJECTOR`` command for the production IME gate.  It
does not start an Activity or change the device volume.  It writes the WAV to
the probe app's private files directory, starts its foreground
``mediaPlayback`` service, and waits for that exact request ID to report
completion.  The phone speaker is therefore the source seen by the phone
microphone; a successful ``am start`` alone is never reported as injection.

Usage::

    python3 scripts/verify/play_device_audio.py /path/to/test.wav

The target serial comes from ``FEELIME_ADB_SERIAL``.  The probe APK must
already be installed and debuggable.  The WAV is temporary device-side test
data and is overwritten on every invocation.
"""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid
import wave


SERIAL = os.environ.get("FEELIME_ADB_SERIAL", "").strip()
PKG = "org.example.feelimeaudioprobe"
SERVICE = PKG + "/.AudioPlaybackService"
ACTION_PLAY = PKG + ".action.PLAY"
EXTRA_REQUEST_ID = PKG + ".extra.REQUEST_ID"
REMOTE_AUDIO = "files/probe-audio.wav"
LOG_TAG = "FeelimeAudioProbe"
DEFAULT_TIMEOUT = float(os.environ.get("FEELIME_AUDIO_HOST_TIMEOUT_S", "180"))
MAX_AUDIO_BYTES = int(os.environ.get("FEELIME_AUDIO_HOST_MAX_BYTES", str(64 * 1024 * 1024)))
MAX_DURATION_S = float(os.environ.get("FEELIME_AUDIO_HOST_MAX_DURATION_S", "120"))


class ProbeError(RuntimeError):
    """A prerequisite or playback completion assertion failed."""


def run_adb(*args, input_data=None, timeout=30):
    if not SERIAL:
        raise ProbeError("FEELIME_ADB_SERIAL is required")
    command = ["adb", "-s", SERIAL, *args]
    try:
        return subprocess.run(
            command,
            input=input_data,
            capture_output=True,
            timeout=timeout,
            check=False,
        )
    except OSError as error:
        raise ProbeError(f"adb unavailable: {error}") from error
    except subprocess.TimeoutExpired as error:
        raise ProbeError(f"adb command timed out: {' '.join(command[2:])}") from error


def output_text(result):
    return ((result.stdout or b"") + (result.stderr or b"")).decode(
        "utf-8", "replace").strip()


def validate_wav(path):
    if not path.is_file():
        raise ProbeError(f"WAV does not exist: {path}")
    size = path.stat().st_size
    if size <= 44:
        raise ProbeError(f"WAV is empty: {path}")
    if size > MAX_AUDIO_BYTES:
        raise ProbeError(f"WAV is larger than {MAX_AUDIO_BYTES} bytes: {path}")
    try:
        with wave.open(str(path), "rb") as reader:
            rate = reader.getframerate()
            channels = reader.getnchannels()
            width = reader.getsampwidth()
            frames = reader.getnframes()
    except (OSError, EOFError, wave.Error) as error:
        raise ProbeError(f"cannot read WAV header: {error}") from error
    if (rate, channels, width) != (16000, 1, 2):
        raise ProbeError(
            "production AudioRecord requires 16 kHz mono S16LE; "
            f"got rate={rate} channels={channels} sampleWidth={width}"
        )
    if frames <= 0 or rate <= 0:
        raise ProbeError("WAV has no audio frames")
    duration_s = frames / float(rate)
    if duration_s <= 0 or duration_s > MAX_DURATION_S:
        raise ProbeError(
            f"WAV duration {duration_s:.3f}s is outside 0..{MAX_DURATION_S:.1f}s"
        )
    data = path.read_bytes()
    return data, {
        "sha256": hashlib.sha256(data).hexdigest(),
        "bytes": len(data),
        "frames": frames,
        "rate": rate,
        "durationMs": round(duration_s * 1000),
    }


def write_private_audio(data, expected_sha):
    # A newly installed service-only app has not created files/ yet.  Quote
    # sh's command for the outer adb shell so redirection happens as the app
    # UID; -T preserves the WAV bytes and shell reports remote exit failures.
    result = run_adb("shell", "-T", "run-as", PKG, "mkdir", "-p", "files", timeout=20)
    if result.returncode != 0:
        raise ProbeError("private audio directory failed: " + output_text(result))
    result = run_adb(
        "shell", "-T", "run-as", PKG, "sh", "-c", shlex.quote("cat > " + REMOTE_AUDIO),
        input_data=data,
        timeout=60,
    )
    if result.returncode != 0:
        raise ProbeError("private audio write failed: " + (output_text(result) or "no output"))

    result = run_adb("shell", "-T", "run-as", PKG, "sha256sum", REMOTE_AUDIO, timeout=20)
    if result.returncode != 0:
        raise ProbeError("private audio checksum failed: " + (output_text(result) or "no output"))
    match = re.search(r"\b([0-9a-fA-F]{64})\b", output_text(result))
    actual = match.group(1).lower() if match else ""
    if actual != expected_sha.lower():
        raise ProbeError(
            "private audio checksum mismatch: "
            f"expected={expected_sha} actual={actual or '<missing>'}"
        )


def read_probe_logs(request_id):
    result = run_adb(
        "shell", "logcat", "-d", "-v", "brief", "-s",
        LOG_TAG + ":I", "*:S", timeout=20,
    )
    if result.returncode != 0:
        raise ProbeError("cannot read probe log: " + (output_text(result) or "no output"))
    return [line for line in output_text(result).splitlines()
            if "requestId=" + request_id in line]


def parse_terminal(lines, request_id):
    error_lines = [line for line in lines if "state=error" in line]
    if error_lines:
        raise ProbeError("audio probe reported an error: " + error_lines[-1])
    completed = [line for line in lines if "state=completed" in line]
    if not completed:
        return None
    line = completed[-1]
    duration = re.search(r"\bdurationMs=(\d+)", line)
    elapsed = re.search(r"\belapsedMs=(\d+)", line)
    if not duration or not elapsed:
        raise ProbeError("completion log lacks duration/elapsed: " + line)
    return {
        "line": line,
        "durationMs": int(duration.group(1)),
        "elapsedMs": int(elapsed.group(1)),
        "started": any("state=started" in item for item in lines),
    }


def validate_completion(completion, expected):
    if not completion.get("started"):
        raise ProbeError("completion log has no matching playback start event")
    actual = completion["durationMs"]
    elapsed = completion["elapsedMs"]
    expected_ms = expected["durationMs"]
    # MediaPlayer may round the container duration by a frame or a few hundred
    # milliseconds.  A broad, explicit bound catches a zero-length/instant
    # completion while accepting normal extractor rounding.
    tolerance = max(1000, round(expected_ms * 0.20))
    if actual <= 0 or abs(actual - expected_ms) > tolerance:
        raise ProbeError(
            f"reported duration is unreasonable: expected={expected_ms} actual={actual}"
        )
    # Completion is emitted only after MediaPlayer has consumed the source.
    # Keep a lower bound to reject a service that merely starts and immediately
    # reports done; allow scheduler and emulator variance on the upper side.
    minimum_elapsed = max(250, round(actual * 0.60))
    maximum_elapsed = max(actual * 3, actual + 5000)
    if elapsed < minimum_elapsed or elapsed > maximum_elapsed:
        raise ProbeError(
            f"playback elapsed time is unreasonable: duration={actual} elapsed={elapsed}"
        )


def play(path):
    data, expected = validate_wav(path)
    digest = expected["sha256"]
    write_private_audio(data, digest)
    request_id = "probe-" + uuid.uuid4().hex
    result = run_adb(
        "shell", "am", "start-foreground-service",
        "-n", SERVICE,
        "-a", ACTION_PLAY,
        "--es", EXTRA_REQUEST_ID, request_id,
        timeout=20,
    )
    if result.returncode != 0:
        raise ProbeError("could not start audio service: " + (output_text(result) or "no output"))

    wait_timeout = max(DEFAULT_TIMEOUT, expected["durationMs"] / 1000.0 + 15)
    deadline = time.monotonic() + wait_timeout
    last_lines = []
    while time.monotonic() < deadline:
        last_lines = read_probe_logs(request_id)
        completion = parse_terminal(last_lines, request_id)
        if completion is not None:
            validate_completion(completion, expected)
            print(
                "Audio injected "
                f"requestId={request_id} sha256={digest} bytes={expected['bytes']} "
                f"durationMs={completion['durationMs']} elapsedMs={completion['elapsedMs']}",
                flush=True,
            )
            return
        time.sleep(0.5)
    detail = last_lines[-1] if last_lines else "no matching playback log"
    raise ProbeError(
        f"audio playback did not complete within {wait_timeout:.1f}s: " + detail
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wav", type=Path, help="16 kHz mono S16LE WAV to play")
    args = parser.parse_args(argv)
    try:
        play(args.wav.expanduser().resolve())
    except ProbeError as error:
        print("play_device_audio.py: " + str(error), file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
