"""Train a personal "Okay Edge" wake-word classifier on openWakeWord speech embeddings.

  train_wakeword.py [--enroll enroll/] [--out ../android/app/src/main/assets/wakeword.json]

Features: openWakeWord's melspectrogram.onnx + embedding_model.onnx (one 96-d embedding per 80 ms).
Classifier: small MLP over the last 16 embeddings (1.28 s), exported as JSON for the app.

Positives: enrollment samples recorded in the app, plus synthetic Piper voices, each placed on random
background audio at random gain/offset. Negatives: everything in the phone session recordings except
the wake-word attempts, plus synthetic near-misses ("Okay.", "Hey Edge", "Okay age", ...).
Held out for evaluation: the real wake attempts in the session recordings and the last part of the
long session, scored as a stream exactly as the app will run it.
"""

import argparse
import glob
import json
import os
import time

import numpy as np
import onnxruntime as ort
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, "..", "models")
REC = os.path.join(HERE, "recordings")
SR = 16000
WINDOW = 16  # embeddings per classifier input (1.28 s)
STEP, CONTEXT = 1280, 480  # 80 ms streaming step, 3 mel hops of left context
# Detection rule shared with WakeWordModel.kt: score above threshold for PATIENCE consecutive steps,
# and the last 1.3 s louder than GATE_DBFS (far-away TV/background speech peaks around -40 dBFS).
PATIENCE, GATE_DBFS, THRESHOLD = 3, -35.0, 0.9
rng = np.random.default_rng(0)

# Real "Okay Edge" attempts found in the phone recordings (seconds): held out for evaluation.
SESSIONS = {
    # Up to 3500 s includes your "Hey Edge" attempts and commands: the best negatives (your voice, not the phrase).
    "session_20261031_145927.fixed.wav": {"attempts": [3514.0, 3516.9, 3521.1, 3528.2, 3539.3, 3547.1, 3935.9, 3939.8], "train_until": 3500},
    "session_20261031_160845.wav": {"attempts": [9.2, 870.0, 886.8, 891.9, 895.9], "train_until": 600},
}
EXTRA_NEGATIVE_SESSIONS = ["session_20261031_163956.wav", "session_20261031_164007.wav"]  # "Hello Edgio"


class Features:
    def __init__(self):
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 4
        d = os.path.join(MODELS, "openwakeword")
        self.mel = ort.InferenceSession(os.path.join(d, "melspectrogram.onnx"), opts)
        self.emb = ort.InferenceSession(os.path.join(d, "embedding_model.onnx"), opts)

    def melspec(self, audio: np.ndarray) -> np.ndarray:
        x = (np.clip(audio, -1, 1) * 32767).astype(np.float32)[None, :]  # model expects int16-scale floats
        return np.squeeze(self.mel.run(None, {"input": x})[0]) / 10 + 2  # [frames, 32]

    def embeddings(self, audio: np.ndarray) -> np.ndarray:
        """[n, 96], computed exactly as WakeWordModel.kt streams it, so training matches the phone.

        Step k feeds samples [1280k, 1280k+1280) with 480 samples of left context (zeros at the
        start) through the mel model (8 frames), and from step 9 on emits one embedding over the
        latest 76 frames. Embedding i therefore ends at audio time 0.08 * (i + 10) seconds.
        """
        x = np.concatenate([np.zeros(CONTEXT, np.float32), np.clip(audio, -1, 1) * 32767]).astype(np.float32)
        steps = (len(x) - CONTEXT) // STEP
        if steps < 10:
            return np.zeros((0, 96), np.float32)
        # One mel call per step: the mel model mixes data across a batch, so batching changes results.
        frames = [self.mel.run(None, {"input": x[None, k * STEP : k * STEP + STEP + CONTEXT]})[0][0, 0] for k in range(steps)]
        mel = np.concatenate(frames) / 10 + 2  # step k -> frames 8k..8k+8
        out = []
        ks = np.arange(9, steps)
        for s0 in range(0, len(ks), 1024):
            wins = np.stack([mel[8 * (k + 1) - 76 : 8 * (k + 1)] for k in ks[s0 : s0 + 1024]])
            out.append(self.emb.run(None, {"input_1": wins[..., None].astype(np.float32)})[0].reshape(-1, 96))
        return np.concatenate(out)


def windows(emb: np.ndarray, stride: int = 1) -> np.ndarray:
    if len(emb) < WINDOW:
        return np.zeros((0, WINDOW * 96), np.float32)
    idx = range(0, len(emb) - WINDOW + 1, stride)
    return np.stack([emb[i : i + WINDOW].reshape(-1) for i in idx]).astype(np.float32)


def load(path: str) -> np.ndarray:
    try:
        a, sr = sf.read(path, dtype="float32", always_2d=True)
        a = a[:, 0]
    except Exception:  # session wavs whose header wasn't finalised
        a, sr = np.fromfile(path, dtype="<i2", offset=44).astype(np.float32) / 32768, SR
    if sr != SR:
        idx = np.linspace(0, len(a) - 1, int(len(a) * SR / sr))
        a = np.interp(idx, np.arange(len(a)), a).astype(np.float32)
    return a


def trim_speech(a: np.ndarray) -> np.ndarray:
    """Cut a short recording down to its loudest contiguous region (the spoken phrase)."""
    frame = 160
    e = np.array([np.sqrt(np.mean(a[i : i + frame] ** 2) + 1e-12) for i in range(0, len(a) - frame, frame)])
    if len(e) == 0:
        return a
    floor, peak = np.percentile(e, 10), e.max()
    # Low cutoff + generous tail: a softly spoken "Edge" after a loud "Okay" must not be trimmed away.
    on = e > floor + 0.05 * (peak - floor)
    idx = np.where(on)[0]
    s, t = max(0, idx[0] - 10), min(len(e), idx[-1] + 20)  # 100 ms before, 200 ms after
    return a[s * frame : t * frame]


def background_pool(paths) -> list[np.ndarray]:
    pool = []
    for p in paths:
        a = load(p)
        for i in range(0, len(a) - 3 * SR, 30 * SR):
            pool.append(a[i : i + 3 * SR])
    return pool


def place(phrase: np.ndarray, pool, end_gap: float) -> np.ndarray:
    """Phrase on a 2.2 s background so it ends `end_gap` seconds before the canvas end."""
    n = int(2.2 * SR)
    bg = pool[rng.integers(len(pool))]
    start = rng.integers(0, max(1, len(bg) - n))
    canvas = bg[start : start + n].copy() * rng.uniform(0.0, 1.0)
    p = phrase[-(n - int(end_gap * SR)) :] * 10 ** (rng.uniform(-12, 6) / 20)
    at = n - int(end_gap * SR) - len(p)
    canvas[at : at + len(p)] += p
    return np.clip(canvas, -1, 1)


def speech_segments(audio: np.ndarray, avoid=(), offset=0.0) -> list[np.ndarray]:
    """VAD-bounded utterances (<= 3 s) from a long recording, skipping wake attempts."""
    import types

    from listen import build_vad

    vad = build_vad(types.SimpleNamespace(end_silence=0.4, max_command=3.0))
    out = []
    for i in range(0, len(audio) - 511, 512):
        vad.accept_waveform(audio[i : i + 512])
        while not vad.empty():
            seg = vad.front
            t = offset + seg.start / SR
            if not any(-2 < t - s_ < 3 for s_ in avoid):
                out.append(np.array(seg.samples, dtype=np.float32))
            vad.pop()
    return out


def positives(feat, phrases, pool, copies) -> np.ndarray:
    out = []
    for ph in phrases:
        for _ in range(copies):
            canvas = place(ph, pool, rng.uniform(0.0, 0.3))
            emb = feat.embeddings(canvas)
            out.append(emb[-WINDOW:].reshape(-1))
    return np.array(out, np.float32)


def synth_phrases(texts) -> list[np.ndarray]:
    from make_test_clips import speak, tts_for

    out = []
    for voice in ["lessac-medium", "ryan-medium", "hfc_female-medium", "joe-medium"]:
        tts = tts_for(voice)
        for text in texts:
            for speed in (0.85, 1.0, 1.2):
                import sherpa_onnx
                from make_test_clips import phonemize

                cfg = sherpa_onnx.GenerationConfig()
                cfg.sid, cfg.speed = 0, speed
                cfg.phoneme_codepoints = [[ord(c) for c in phonemize(text)]]
                g = tts.generate(text, cfg)
                a = np.array(g.samples, np.float32)
                idx = np.linspace(0, len(a) - 1, int(len(a) * SR / g.sample_rate))
                out.append(trim_speech(np.interp(idx, np.arange(len(a)), a).astype(np.float32)))
    return out


class MLP:
    """1536 -> 64 (relu) -> 1 (sigmoid), trained with Adam and class-balanced weights."""

    def __init__(self, n_in, hidden=64):
        self.mean = np.zeros(n_in, np.float32)
        self.std = np.ones(n_in, np.float32)
        self.w1 = (rng.standard_normal((n_in, hidden)) * np.sqrt(2 / n_in)).astype(np.float32)
        self.b1 = np.zeros(hidden, np.float32)
        self.w2 = (rng.standard_normal((hidden, 1)) * np.sqrt(1 / hidden)).astype(np.float32)
        self.b2 = np.zeros(1, np.float32)

    def scores(self, x):
        h = np.maximum(0, ((x - self.mean) / self.std) @ self.w1 + self.b1)
        return 1 / (1 + np.exp(-(h @ self.w2 + self.b2)[:, 0]))

    def fit(self, x, y, weights, epochs=40, lr=1e-3, l2=1e-4, batch=256):
        self.mean, self.std = x.mean(0), x.std(0) + 1e-3
        params = [self.w1, self.b1, self.w2, self.b2]
        m = [np.zeros_like(p) for p in params]
        v = [np.zeros_like(p) for p in params]
        t = 0
        for _ in range(epochs):
            order = rng.permutation(len(x))
            for s in range(0, len(x), batch):
                i = order[s : s + batch]
                xb = (x[i] - self.mean) / self.std
                yb, wb = y[i], weights[i]
                z1 = xb @ self.w1 + self.b1
                h = np.maximum(0, z1)
                p = 1 / (1 + np.exp(-(h @ self.w2 + self.b2)[:, 0]))
                g = ((p - yb) * wb / wb.sum())[:, None]
                gw2 = h.T @ g + l2 * self.w2
                gb2 = g.sum(0)
                gh = (g @ self.w2.T) * (z1 > 0)
                gw1 = xb.T @ gh + l2 * self.w1
                gb1 = gh.sum(0)
                t += 1
                for k, (prm, grd) in enumerate(zip(params, [gw1, gb1, gw2, gb2])):
                    m[k] = 0.9 * m[k] + 0.1 * grd
                    v[k] = 0.999 * v[k] + 0.001 * grd * grd
                    prm -= lr * (m[k] / (1 - 0.9 ** t)) / (np.sqrt(v[k] / (1 - 0.999 ** t)) + 1e-8)

    def export(self, path, threshold):
        with open(path, "w") as f:
            json.dump({"window": WINDOW, "threshold": threshold, "patience": PATIENCE, "gate_dbfs": GATE_DBFS, "mean": self.mean.tolist(), "std": self.std.tolist(),
                       "w1": self.w1.tolist(), "b1": self.b1.tolist(), "w2": self.w2[:, 0].tolist(),
                       "b2": float(self.b2[0])}, f)


def stream_detections(model, emb, threshold, audio, refractory=2.0):
    """Times (s) at which the app would fire (same rule as WakeWordModel.kt)."""
    if len(emb) < WINDOW:
        return []
    s = model.scores(windows(emb))
    hits, last, run = [], -1e9, 0
    for i, v in enumerate(s):
        t = (i + WINDOW - 1 + 10) * 0.08  # end of the audio this window covers
        recent = audio[max(0, int((t - 1.3) * SR)) : int(t * SR)]
        loud = len(recent) and 20 * np.log10(np.abs(recent).max() + 1e-9) > GATE_DBFS
        run = run + 1 if v > threshold and loud else 0
        if run >= PATIENCE and t - last > refractory:
            hits.append(t)
            last = t
    return hits


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--enroll", default=os.path.join(HERE, "enroll"))
    p.add_argument("--out", default=os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "wakeword.json"))
    p.add_argument("--copies", type=int, default=6, help="augmented copies per positive phrase")
    a = p.parse_args()
    t0 = time.time()
    feat = Features()

    session_audio = {name: load(os.path.join(REC, name)) for name in SESSIONS}
    train_bg = [a_[: int(cfg["train_until"] * SR)] for (name, cfg), a_ in zip(SESSIONS.items(), session_audio.values())]
    pool = []
    for bg in train_bg:
        for i in range(0, len(bg) - 3 * SR, 20 * SR):
            pool.append(bg[i : i + 3 * SR])
    pool += background_pool(sorted(glob.glob(os.path.join(HERE, "test_clips", "neg_*.wav"))))
    print(f"background pool: {len(pool)} clips")

    # Positives
    enroll = sorted(glob.glob(os.path.join(a.enroll, "*.wav")))
    user_phrases = [trim_speech(load(f)) for f in enroll]
    synth = synth_phrases(["Okay Edge.", "OK Edge."])
    print(f"positives: {len(user_phrases)} enrollment samples, {len(synth)} synthetic")
    pos = [positives(feat, user_phrases, pool, a.copies * 3)] if user_phrases else []
    pos.append(positives(feat, synth, pool, a.copies))
    pos = np.concatenate(pos)

    # Negatives: training part of the sessions (wake attempts excluded), synthetic near-misses.
    neg = []
    for (name, cfg), audio in zip(SESSIONS.items(), session_audio.values()):
        part = audio[: int(cfg["train_until"] * SR)]
        w = windows(feat.embeddings(part), stride=2)
        ends = (np.arange(len(w)) * 2 + WINDOW - 1 + 10) * 0.08
        keep = [i for i, t in enumerate(ends) if not any(-1 < t - s < 4 for s in cfg["attempts"])]
        neg.append(w[keep])
    for f in sorted(glob.glob(os.path.join(HERE, "test_clips", "neg_*.wav")) + glob.glob(os.path.join(HERE, "phrase_clips", "neg_*.wav"))
                    + glob.glob(os.path.join(HERE, "phrase_clips", "conf_*.wav"))):
        neg.append(windows(feat.embeddings(load(f)), stride=1))
    confusers = synth_phrases(["Okay.", "Okay age.", "Hey Edge.", "Edge.", "Okay, let's go.", "Okay Google.", "OK, I get it.",
                               "Hello Edge.", "Okay, sure."])
    neg.append(positives(feat, confusers, pool, 3))
    # Speech negatives built exactly like positives (pasted on background), so construction isn't a cue:
    # your own non-wake utterances from the sessions, and synthetic everyday sentences.
    own = []
    for (name, cfg), audio in zip(SESSIONS.items(), session_audio.values()):
        own += speech_segments(audio[: int(cfg["train_until"] * SR)], cfg["attempts"])
    synth_speech = []
    for f in sorted(glob.glob(os.path.join(HERE, "phrase_clips", "neg_*.wav")) + glob.glob(os.path.join(HERE, "phrase_clips", "conf_*.wav"))):
        synth_speech += speech_segments(load(f))
    print(f"speech negatives: {len(own)} of your utterances, {len(synth_speech)} synthetic")
    neg.append(positives(feat, own, pool, 4))
    not_wake = [trim_speech(load(f_)) for f_ in sorted(glob.glob(os.path.join(a.enroll, "not_wake", "*.wav")))]
    if not_wake:
        print(f"not-wake samples: {len(not_wake)}")
        neg.append(positives(feat, not_wake, pool, 12))
    neg.append(positives(feat, synth_speech, pool, 1))
    neg = np.concatenate(neg)
    print(f"negatives: {len(neg)} windows; features ready in {time.time() - t0:.0f}s")

    x = np.concatenate([pos, neg])
    y = np.concatenate([np.ones(len(pos)), np.zeros(len(neg))]).astype(np.float32)
    w = np.where(y == 1, len(neg) / len(pos) * 0.5, 1.0).astype(np.float32)
    model = MLP(x.shape[1])
    model.fit(x, y, w)
    # Hard-negative pass: upweight the negatives the first model scores highest.
    for _ in range(2):
        s = model.scores(neg)
        hard = s > 0.3
        w[len(pos):][hard] *= 10
        model.fit(x, y, w, epochs=20)
    print(f"trained in {time.time() - t0:.0f}s; hard negatives: {int(hard.sum())}")

    # Held-out evaluation, streamed like the app.
    print("\n== held-out evaluation (real phone audio not used for training)")
    held = {name: audio[int(cfg["train_until"] * SR) - SR * 2 :] for (name, cfg), audio in zip(SESSIONS.items(), session_audio.values())}
    offsets = {name: cfg["train_until"] - 2 for name, cfg in SESSIONS.items()}
    held_emb = {name: feat.embeddings(audio) for name, audio in held.items()}
    extra_emb = {name: feat.embeddings(load(os.path.join(REC, name))) for name in EXTRA_NEGATIVE_SESSIONS}
    held_minutes = (sum(len(v) for v in held.values()) + sum(len(load(os.path.join(REC, n))) for n in EXTRA_NEGATIVE_SESSIONS)) / SR / 60
    results = []
    for thr in (0.5, 0.7, 0.8, THRESHOLD, 0.95):
        caught = total = false = 0
        for name, cfg in SESSIONS.items():
            hits = [h + offsets[name] for h in stream_detections(model, held_emb[name], thr, held[name])]
            attempts = [s_ for s_ in cfg["attempts"] if s_ >= cfg["train_until"]]
            for s_ in attempts:
                total += 1
                caught += any(s_ - 1.5 < h < s_ + 3.5 for h in hits)
            false += sum(not any(s_ - 1.5 < h < s_ + 3.5 for s_ in cfg["attempts"]) for h in hits)
        for name in EXTRA_NEGATIVE_SESSIONS:
            false += len(stream_detections(model, extra_emb[name], thr, load(os.path.join(REC, name))))
        print(f"  threshold {thr:.2f}: real attempts caught {caught}/{total}, false wakes {false} in {held_minutes:.0f} min")
        results.append((thr, caught, false))
    # Most real attempts caught, then fewest false wakes, then the stricter threshold.
    best = max(results, key=lambda r: (r[1], -r[2], r[0]))
    threshold = THRESHOLD if next(r for r in results if r[0] == THRESHOLD)[1] == best[1] else best[0]
    model.export(a.out, threshold)
    print(f"\nexported {a.out} (threshold {threshold})")


if __name__ == "__main__":
    main()
