"""Stream laptop audio into the Android app (debug build, "Use laptop mic" switch) over adb.

  mic_bridge.py                    live laptop microphone
  mic_bridge.py --wav a.wav b.wav  play files in real time (for repeatable tests), then exit

The emulator's own host-mic passthrough stops delivering audio once the app reopens the mic,
so this bypasses emulator audio entirely: PCM goes over `adb forward` to a loopback socket.
"""

import argparse
import os
import queue
import socket
import subprocess
import sys
import time

import numpy as np

PORT = 8765
SR = 16000
CHUNK = SR // 10
ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")


def connect() -> socket.socket:
    subprocess.run([ADB, "forward", f"tcp:{PORT}", f"tcp:{PORT}"], check=True, capture_output=True)
    print("waiting for the app (switch on 'Use laptop mic', then Start listening)...", flush=True)
    while True:
        try:
            s = socket.create_connection(("127.0.0.1", PORT), timeout=2)
            # adb accepts the forward even when nothing listens on the device; a send probes it.
            s.sendall(b"\0\0" * CHUNK)
            time.sleep(0.3)
            s.sendall(b"\0\0" * CHUNK)
            print("connected", flush=True)
            return s
        except OSError:
            time.sleep(1)


def to_pcm(samples: np.ndarray) -> bytes:
    return (np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes()


def meter(samples: np.ndarray) -> str:
    peak = float(np.abs(samples).max()) if len(samples) else 0.0
    bars = int(min(1.0, peak ** 0.5) * 30)
    return f"\r[{'#' * bars}{' ' * (30 - bars)}] {peak:.2f}"


def stream_mic(sock: socket.socket, device):
    import sounddevice as sd

    if device is None:
        names = [d["name"] for d in sd.query_devices()]
        device = next((n for n in ("pipewire", "pulse") if n in names), None)
    q: queue.Queue = queue.Queue()
    with sd.InputStream(channels=1, samplerate=SR, dtype="float32", blocksize=CHUNK, device=device,
                        callback=lambda data, *_: q.put(data[:, 0].copy())):
        print("streaming laptop mic; Ctrl+C to stop", flush=True)
        while True:
            chunk = q.get()
            sock.sendall(to_pcm(chunk))
            print(meter(chunk), end="", flush=True)


def stream_wavs(sock: socket.socket, paths, gap: float):
    import soundfile as sf

    silence = np.zeros(int(gap * SR), dtype=np.float32)
    for path in paths:
        audio, sr = sf.read(path, dtype="float32", always_2d=True)
        audio = audio[:, 0]
        if sr != SR:
            idx = np.linspace(0, len(audio) - 1, int(len(audio) * SR / sr))
            audio = np.interp(idx, np.arange(len(audio)), audio).astype(np.float32)
        print(f"playing {os.path.basename(path)}", flush=True)
        audio = np.concatenate([audio, silence])
        start = time.monotonic()
        for i in range(0, len(audio), CHUNK):
            sock.sendall(to_pcm(audio[i : i + CHUNK]))
            # Pace at real time so the app's timeouts behave as with a live mic.
            ahead = (i + CHUNK) / SR - (time.monotonic() - start)
            if ahead > 0:
                time.sleep(ahead)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--wav", nargs="+", help="stream these files instead of the live mic")
    p.add_argument("--gap", type=float, default=3.0, help="silence after each file, seconds")
    p.add_argument("--device", help="input device (see python -m sounddevice)")
    a = p.parse_args()

    sock = connect()
    try:
        if a.wav:
            stream_wavs(sock, a.wav, a.gap)
        else:
            stream_mic(sock, a.device)
    except KeyboardInterrupt:
        print()
    except (BrokenPipeError, ConnectionResetError):
        sys.exit("\napp closed the connection (listening stopped?)")
    finally:
        sock.close()


if __name__ == "__main__":
    main()
