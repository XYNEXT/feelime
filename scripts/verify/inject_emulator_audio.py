#!/usr/bin/env python3
"""Run the official emulator audio sample with a deterministic tail.

The AOSP ``inject_audio.py`` sample stays outside this repository.  Point
``FEELIME_ASR_SAMPLE`` at that file and provide the gRPC port of the one target
emulator through ``FEELIME_ASR_GRPC_PORT``.  All other command-line arguments
are passed to the sample unchanged.
"""

import importlib.util
import os
import sys
import time
import wave
from pathlib import Path


SILENCE_CHUNKS = 4
SILENCE_CHUNK_MS = 300


def _required_env(name):
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"{name} is required")
    return value


def _load_sample(path):
    sample_path = Path(path).expanduser().resolve()
    if not sample_path.is_file():
        raise RuntimeError(f"FEELIME_ASR_SAMPLE does not point to a file: {sample_path}")
    spec = importlib.util.spec_from_file_location("feelime_official_inject_audio", sample_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load official sample: {sample_path}")
    module = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(module)
    except Exception as error:
        raise RuntimeError(f"official sample failed to load: {sample_path}") from error
    return module


def _find_target(port):
    try:
        from aemu.discovery.emulator_discovery import EmulatorDiscovery
    except Exception as error:
        raise RuntimeError("aemu discovery is unavailable in the current Python environment") from error
    try:
        emulator = EmulatorDiscovery().find_emulator("grpc.port", port)
    except Exception as error:
        raise RuntimeError(f"emulator discovery failed for grpc.port={port}") from error
    if emulator is None:
        raise RuntimeError(f"no unique emulator found for grpc.port={port}")
    return emulator


def _silence_audio(wavefile):
    try:
        with wave.open(str(wavefile), "rb") as reader:
            frame_rate = reader.getframerate()
            channels = reader.getnchannels()
            sample_width = reader.getsampwidth()
    except (OSError, EOFError, wave.Error) as error:
        raise RuntimeError(f"cannot read WAV format: {wavefile}") from error
    frames = frame_rate * SILENCE_CHUNK_MS // 1000
    frame_width = channels * sample_width
    if frame_rate <= 0 or channels <= 0 or sample_width <= 0 or frames <= 0:
        raise RuntimeError(f"WAV has an invalid audio format: {wavefile}")
    return bytes([128 if sample_width == 1 else 0]) * (frames * frame_width)


def _patch_audio_packets(module):
    original_packets = module.read_wav_file

    def packets_with_tail(wavefile, realtime=False):
        silence = _silence_audio(wavefile)
        last_packet = None
        for packet in original_packets(wavefile, realtime):
            last_packet = packet
            yield packet
        if last_packet is None:
            raise RuntimeError("official sample produced no audio packets")
        for index in range(SILENCE_CHUNKS):
            padded = type(last_packet)()
            padded.CopyFrom(last_packet)
            padded.audio = silence
            yield padded
            if realtime and index + 1 < SILENCE_CHUNKS:
                time.sleep(SILENCE_CHUNK_MS / 1000.0)

    module.read_wav_file = packets_with_tail


def main():
    sample_path = _required_env("FEELIME_ASR_SAMPLE")
    grpc_port = _required_env("FEELIME_ASR_GRPC_PORT")
    module = _load_sample(sample_path)
    module.get_default_emulator = lambda: _find_target(grpc_port)
    _patch_audio_packets(module)
    # The official sample owns argument parsing and must receive the original
    # argv, including its program name.
    module.main(sys.argv)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as error:
        print(f"inject_emulator_audio.py: {error}", file=sys.stderr)
        raise SystemExit(1) from error
