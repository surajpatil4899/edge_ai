"""Analyse a phone session recording (Save session audio) to tune the wake word and pick an ASR model.

  eval_session.py recordings/session_X.wav [--script recording_script.txt]

Reports mic levels, wake-word detections under several spotter settings, and per-utterance
transcripts from each ASR model, scored against the lines that were read aloud.
"""

import argparse
import os
import re
import time
import types

import numpy as np
import sherpa_onnx
import soundfile as sf

from listen import MODELS, build_asr, build_kws, build_vad

SR = 16000
CANARY = os.path.join(MODELS, "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8")
NUMBERS = {"0": "zero", "1": "one", "2": "two", "3": "three", "4": "four", "5": "five", "6": "six", "7": "seven",
           "8": "eight", "9": "nine", "10": "ten", "11": "eleven", "12": "twelve", "30": "thirty"}


def db(x: float) -> str:
    return f"{20 * np.log10(max(x, 1e-9)):6.1f} dBFS"


def normalize(text: str) -> list[str]:
    t = text.lower().replace("a.m.", "am").replace("p.m.", "pm").replace(":", " ")
    t = re.sub(r"(\d+)(am|pm)", r"\1 \2", t)
    words = re.findall(r"[a-z]+|\d+", t)
    return [NUMBERS.get(w, w) for w in words]


def wer(ref: str, hyp: str) -> float:
    r, h = normalize(ref), normalize(hyp)
    d = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, hw in enumerate(h, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (rw != hw))
    return d[-1] / max(1, len(r))


def spot(audio: np.ndarray, boost: float, threshold: float) -> list[float]:
    kws = build_kws(types.SimpleNamespace(keyword=["hey edge"], kws_boost=boost, kws_threshold=threshold))
    stream, hits = kws.create_stream(), []
    for i in range(0, len(audio), 1600):
        stream.accept_waveform(SR, audio[i : i + 1600])
        while kws.is_ready(stream):
            kws.decode_stream(stream)
            if kws.get_result(stream):
                hits.append((i + 1600) / SR)
                stream = kws.create_stream()  # fresh stream: no re-trigger on the same audio
                break
    return hits


def segments(audio: np.ndarray) -> list[tuple[float, np.ndarray]]:
    vad = build_vad(types.SimpleNamespace(end_silence=0.6, max_command=12.0))
    out = []
    for i in range(0, len(audio) - 511, 512):
        vad.accept_waveform(audio[i : i + 512])
        while not vad.empty():
            seg = vad.front
            start = max(0, seg.start - int(0.2 * SR))  # same 200 ms back-pad as the app
            out.append((seg.start / SR, audio[start : seg.start + len(seg.samples)]))
            vad.pop()
    vad.flush()
    while not vad.empty():
        out.append((vad.front.start / SR, np.array(vad.front.samples, dtype=np.float32)))
        vad.pop()
    return out


def recognizers():
    rs = {"moonshine-tiny": build_asr("tiny"), "moonshine-base": build_asr("base")}
    if os.path.isdir(CANARY):
        rs["canary-180m"] = sherpa_onnx.OfflineRecognizer.from_nemo_canary(
            encoder=f"{CANARY}/encoder.int8.onnx", decoder=f"{CANARY}/decoder.int8.onnx",
            tokens=f"{CANARY}/tokens.txt", src_lang="en", tgt_lang="en", num_threads=2)
    return rs


def transcribe(rec, audio: np.ndarray, gain_to: float | None) -> tuple[str, float]:
    if gain_to is not None:
        peak = float(np.abs(audio).max())
        if peak > 0:
            audio = audio * min(gain_to / peak, 20.0)
    s = rec.create_stream()
    s.accept_waveform(SR, audio)
    t = time.perf_counter()
    rec.decode_stream(s)
    return s.result.text.strip(), (time.perf_counter() - t) * 1000


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("wav")
    p.add_argument("--script", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "recording_script.txt"))
    a = p.parse_args()

    audio, sr = sf.read(a.wav, dtype="float32")
    assert sr == SR, sr
    print(f"== {os.path.basename(a.wav)}: {len(audio) / SR:.0f}s")

    segs = segments(audio)
    speech = np.concatenate([s for _, s in segs]) if segs else audio
    print(f"levels: overall rms {db(float(np.sqrt(np.mean(audio ** 2))))}, speech rms "
          f"{db(float(np.sqrt(np.mean(speech ** 2))))}, speech peak {db(float(np.abs(speech).max()))}")
    print(f"         (typical close-talk speech: rms around -25 dBFS, peak around -6 dBFS)")

    print("\n== wake word detections (seconds into the session)")
    for boost, thr in [(2.5, 0.1), (3.0, 0.05), (4.0, 0.03)]:
        for gain in (None, 0.5):
            x = audio if gain is None else audio * min(gain / max(float(np.abs(speech).max()), 1e-6), 20.0)
            hits = spot(x, boost, thr)
            tag = "raw" if gain is None else "normalized"
            print(f"  boost={boost} thr={thr} {tag:10s} {len(hits):2d} hits: {' '.join(f'{h:.1f}' for h in hits)}")

    script = []
    if os.path.exists(a.script):
        script = [l.strip() for l in open(a.script) if l.strip() and not l.startswith("#")]

    print(f"\n== {len(segs)} utterances")
    recs = recognizers()
    totals = {f"{n}{g}": [] for n in recs for g in ("", "+gain")}
    times = {n: [] for n in recs}
    for start, seg in segs:
        print(f"\n[{start:6.1f}s, {len(seg) / SR:.1f}s, peak {db(float(np.abs(seg).max()))}]")
        heard = {}
        for name, rec in recs.items():
            for gain in (None, 0.5):
                text, ms = transcribe(rec, seg, gain)
                key = name + ("" if gain is None else "+gain")
                heard[key] = text
                if gain is None:
                    times[name].append(ms)
        # Match to the script line that the best of the models gets closest to; skip free talk.
        best_ref = min(script, key=lambda ref: min(wer(ref, t or "x") for t in heard.values())) if script else None
        if best_ref and min(wer(best_ref, t or "x") for t in heard.values()) > 0.5:
            best_ref = None
        for key, text in heard.items():
            score = ""
            if best_ref:
                e = wer(best_ref, text)
                totals[key].append(e)
                score = f"  WER {e:.0%}"
            print(f"  {key:22s} {text!r}{score}")
        if best_ref:
            print(f"  {'(closest script line)':22s} {best_ref!r}")

    print("\n== summary (mean WER over utterances matched to the script; lower is better)")
    for key, errs in totals.items():
        if errs:
            name = key.replace("+gain", "")
            print(f"  {key:22s} WER {np.mean(errs):5.1%} over {len(errs)} utterances, "
                  f"decode {np.mean(times[name]):.0f} ms avg (laptop)")


if __name__ == "__main__":
    main()
