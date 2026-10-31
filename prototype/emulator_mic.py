"""Control the Android emulator's microphone over its gRPC API.

  emulator_mic.py status            is host-mic passthrough on?
  emulator_mic.py enable|disable    toggle host-mic passthrough (same as the Extended controls switch)
  emulator_mic.py inject a.wav ...  stream wav files straight into the emulated microphone
"""

import argparse
import configparser
import glob
import os
import subprocess
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
STUBS = os.path.join(HERE, ".emu_grpc")
PROTO_DIR = os.path.expanduser("~/Android/Sdk/emulator/lib")


def load_stubs():
    if not os.path.exists(os.path.join(STUBS, "emulator_controller_pb2_grpc.py")):
        os.makedirs(STUBS, exist_ok=True)
        subprocess.run([sys.executable, "-m", "grpc_tools.protoc", f"-I{PROTO_DIR}", f"--python_out={STUBS}",
                        f"--grpc_python_out={STUBS}", os.path.join(PROTO_DIR, "emulator_controller.proto")], check=True)
    sys.path.insert(0, STUBS)
    import emulator_controller_pb2 as pb
    import emulator_controller_pb2_grpc as pb_grpc

    return pb, pb_grpc


def connect():
    import grpc

    runtime = os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")
    found = sorted(glob.glob(os.path.join(runtime, "avd", "running", "pid_*.ini")))
    if not found:
        sys.exit("no running emulator found")
    ini = configparser.ConfigParser()
    with open(found[-1]) as f:
        ini.read_string("[e]\n" + f.read())
    port, token = ini["e"]["grpc.port"], ini["e"].get("grpc.token")
    channel = grpc.insecure_channel(f"localhost:{port}")
    metadata = [("authorization", f"Bearer {token}")] if token else []
    return channel, metadata


def packets(pb, paths, gap):
    import soundfile as sf

    fmt = pb.AudioFormat(samplingRate=16000, channels=pb.AudioFormat.Mono, format=pb.AudioFormat.AUD_FMT_S16)
    frame = 320  # 20 ms
    for path in paths:
        audio, sr = sf.read(path, dtype="float32", always_2d=True)
        audio = audio[:, 0]
        if sr != 16000:
            idx = np.linspace(0, len(audio) - 1, int(len(audio) * 16000 / sr))
            audio = np.interp(idx, np.arange(len(audio)), audio).astype(np.float32)
        audio = np.concatenate([audio, np.zeros(int(gap * 16000), dtype=np.float32)])
        pcm = (np.clip(audio, -1, 1) * 32767).astype("<i2")
        print(f"injecting {os.path.basename(path)} ({len(audio) / 16000:.1f}s incl. {gap}s silence)", flush=True)
        for i in range(0, len(pcm), frame):
            yield pb.AudioPacket(format=fmt, timestamp=int(time.time() * 1e6), audio=pcm[i : i + frame].tobytes())


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["status", "enable", "disable", "inject"])
    p.add_argument("wavs", nargs="*")
    p.add_argument("--gap", type=float, default=3.0, help="silence after each clip, seconds")
    a = p.parse_args()

    pb, pb_grpc = load_stubs()
    channel, md = connect()
    stub = pb_grpc.EmulatorControllerStub(channel)
    from google.protobuf import empty_pb2

    if a.command == "status":
        print("host microphone passthrough:", stub.getMicrophoneState(empty_pb2.Empty(), metadata=md).realAudioEnabled)
    elif a.command in ("enable", "disable"):
        stub.setMicrophoneState(pb.MicrophoneState(realAudioEnabled=a.command == "enable"), metadata=md)
        print("host microphone passthrough:", stub.getMicrophoneState(empty_pb2.Empty(), metadata=md).realAudioEnabled)
    else:
        if not a.wavs:
            sys.exit("inject needs at least one wav file")
        stub.injectAudio(packets(pb, a.wavs, a.gap), metadata=md)
        print("done")


if __name__ == "__main__":
    main()
