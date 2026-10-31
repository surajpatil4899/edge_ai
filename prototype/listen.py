"""Desktop prototype: wake word ("Okay Edge") -> VAD-bounded command -> Moonshine ASR."""

import argparse
import glob
import os
import queue
import re
import sys
import time
from dataclasses import dataclass

import numpy as np
import sherpa_onnx

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS = os.path.join(ROOT, "models")
KWS_DIR = os.path.join(MODELS, "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01")
SAMPLE_RATE = 16000
CHUNK = 1600  # 100 ms
# Second-stage check: the verifier transcript must contain the wake word itself, which
# rejects near-homophones the spotter confuses with "hey edge" ("hey Ed", "hey judge").
WAKE_WORD = "edge"
NOT_WAKE = {"hedge", "ledge", "wedge", "sedge"}


def edit_distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def confirms_wake(text: str) -> bool:
    # ASR spells the made-up word loosely ("Edg", "Edj"), so allow one edit.
    return any(edit_distance(w, WAKE_WORD) <= 1 and w not in NOT_WAKE for w in re.findall(r"[a-z]+", text.lower()))


def model_file(directory: str, prefix: str, int8: bool = True) -> str:
    matches = sorted(glob.glob(os.path.join(directory, f"{prefix}*.onnx")))
    preferred = [m for m in matches if (".int8." in m) == int8]
    if not preferred:
        sys.exit(f"missing {prefix}*.onnx in {directory}; run scripts/download_models.sh")
    return preferred[0]


def keyword_lines(phrases: list[str], boost: float, threshold: float, variants: int) -> list[str]:
    """One line per BPE spelling of each phrase; extra spellings give the spotter more paths to match."""
    import sentencepiece as spm

    sp = spm.SentencePieceProcessor(model_file=os.path.join(KWS_DIR, "bpe.model"))
    lines = []
    for phrase in phrases:
        label = phrase.upper().replace(" ", "_")
        spellings = sp.nbest_encode(phrase.upper(), nbest_size=variants, out_type=str) if variants > 1 else [
            sp.encode(phrase.upper(), out_type=str)]
        for toks in dict.fromkeys(tuple(t) for t in spellings):
            lines.append(f"{' '.join(toks)} :{boost} #{threshold} @{label}")
    return lines


def build_kws(args) -> sherpa_onnx.KeywordSpotter:
    lines = keyword_lines(args.keyword, args.kws_boost, args.kws_threshold, getattr(args, "kws_variants", 1))
    keywords = os.path.join(KWS_DIR, "keywords_edge.txt")
    with open(keywords, "w") as f:
        f.write("\n".join(lines) + "\n")
    int8 = not getattr(args, "kws_fp32", False)
    return sherpa_onnx.KeywordSpotter(
        tokens=os.path.join(KWS_DIR, "tokens.txt"),
        encoder=model_file(KWS_DIR, "encoder", int8),
        decoder=model_file(KWS_DIR, "decoder", int8),
        joiner=model_file(KWS_DIR, "joiner", int8),
        keywords_file=keywords,
        num_threads=1,
        keywords_threshold=args.kws_threshold,
        keywords_score=args.kws_boost,
    )


def build_vad(args) -> sherpa_onnx.VoiceActivityDetector:
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = os.path.join(MODELS, "silero_vad.onnx")
    cfg.silero_vad.threshold = 0.5
    cfg.silero_vad.min_silence_duration = args.end_silence
    cfg.silero_vad.min_speech_duration = 0.25
    cfg.silero_vad.max_speech_duration = args.max_command
    cfg.sample_rate = SAMPLE_RATE
    return sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=30)


def build_asr(size: str) -> sherpa_onnx.OfflineRecognizer:
    d = os.path.join(MODELS, f"sherpa-onnx-moonshine-{size}-en-quantized-2026-02-27")
    return sherpa_onnx.OfflineRecognizer.from_moonshine_v2(
        encoder=os.path.join(d, "encoder_model.ort"),
        decoder=os.path.join(d, "decoder_model_merged.ort"),
        tokens=os.path.join(d, "tokens.txt"),
        num_threads=2,
    )


@dataclass
class Command:
    text: str
    audio_seconds: float
    asr_ms: float


class Pipeline:
    """Feed 16 kHz float32 audio; yields events as wake word / commands are detected."""

    def __init__(self, args):
        self.args = args
        self.kws = None if args.no_wake else build_kws(args)
        self.vad = build_vad(args)
        self.asr = build_asr(args.asr)
        verify = getattr(args, "verify", None)
        self.verifier = None if not verify or not self.kws else (self.asr if verify == args.asr else build_asr(verify))
        self.window = 512  # silero VAD window at 16 kHz
        self.reset()

    def reset(self):
        if self.kws:
            # A fresh stream restarts the spotter's clock, so timestamps index into self.history.
            self.kws_stream = self.kws.create_stream()
            self.kws_fed = 0
            self.history = np.zeros(0, dtype=np.float32)
        self.vad.reset()
        self.vad_buf = np.zeros(0, dtype=np.float32)
        self.vad_audio = np.zeros(0, dtype=np.float32)
        self.vad_audio_offset = 0
        self.listening = self.kws is None
        self.heard_speech = False

    def start_listening(self, now: float):
        self.vad.reset()
        self.vad_buf = np.zeros(0, dtype=np.float32)
        self.vad_audio = np.zeros(0, dtype=np.float32)
        self.vad_audio_offset = 0
        self.listening = True
        self.listen_started = now
        self.heard_speech = False

    def feed(self, samples: np.ndarray, now: float):
        if not self.listening:
            self.kws_stream.accept_waveform(SAMPLE_RATE, samples)
            self.kws_fed += len(samples)
            # The spotter can report a keyword seconds late during continuous speech, so keep a long tail.
            self.history = np.concatenate([self.history, samples])[-10 * SAMPLE_RATE :]
            preroll = None
            while self.kws.is_ready(self.kws_stream):
                self.kws.decode_stream(self.kws_stream)
                # The wrapper's getters each re-fetch (and clear) the result, so read it once.
                hit = self.kws.keyword_spotter.get_result(self.kws_stream)
                if hit.keyword:
                    if self.verifier:
                        heard = self.verify_wake(hit.timestamps)
                        if not confirms_wake(heard):
                            yield ("rejected", heard)
                            self.restart_spotter()
                            continue
                    preroll = self.audio_after_keyword(hit.timestamps)
                    yield ("wake", hit.keyword.strip())
                    break
            if preroll is None:
                return
            # The spotter fires ~0.5 s after the phrase ends; replay that audio so the command isn't clipped.
            self.start_listening(now)
            samples = preroll

        self.vad_buf = np.concatenate([self.vad_buf, samples])
        self.vad_audio = np.concatenate([self.vad_audio, samples])
        if len(self.vad_audio) > 30 * SAMPLE_RATE:
            drop = len(self.vad_audio) - 30 * SAMPLE_RATE
            self.vad_audio = self.vad_audio[drop:]
            self.vad_audio_offset += drop
        while len(self.vad_buf) >= self.window:
            self.vad.accept_waveform(self.vad_buf[: self.window])
            self.vad_buf = self.vad_buf[self.window :]
        if self.vad.is_speech_detected():
            self.heard_speech = True

        while not self.vad.empty():
            yield ("command", self.transcribe(self.pop_segment()))
            if self.kws:
                self.reset()
                return

        if self.kws and not self.heard_speech and now - self.listen_started > self.args.wait_command:
            self.reset()
            yield ("timeout", None)

    def restart_spotter(self):
        # New stream restarts timestamps at 0; history stays aligned because both index back from "now".
        self.kws_stream = self.kws.create_stream()
        self.kws_fed = 0

    def verify_wake(self, timestamps) -> str:
        # Some context either side of the keyword helps the ASR resolve "edge" vs "Ed".
        start = (timestamps[0] - 0.5) if timestamps else self.kws_fed / SAMPLE_RATE - 2.0
        end = (timestamps[-1] + 0.5) if timestamps else self.kws_fed / SAMPLE_RATE
        a = max(0, len(self.history) - (self.kws_fed - int(start * SAMPLE_RATE)))
        b = max(a, len(self.history) - max(0, self.kws_fed - int(end * SAMPLE_RATE)))
        s = self.verifier.create_stream()
        s.accept_waveform(SAMPLE_RATE, self.history[a:b])
        self.verifier.decode_stream(s)
        return s.result.text.strip()

    def pop_segment(self) -> np.ndarray:
        # Silero triggers a little after speech onset, which clips plosives ("Call" -> "all"); pad backwards.
        seg = self.vad.front
        start = seg.start - self.vad_audio_offset
        end = start + len(seg.samples)
        self.vad.pop()
        pad = int(0.2 * SAMPLE_RATE)
        if start < 0 or end > len(self.vad_audio):
            return np.array(seg.samples, dtype=np.float32)
        return self.vad_audio[max(0, start - pad) : end]

    def audio_after_keyword(self, timestamps) -> np.ndarray:
        # Last token timestamp marks the start of the final BPE piece; add ~one token of duration.
        end = timestamps[-1] + 0.12 if timestamps else self.kws_fed / SAMPLE_RATE - 0.5
        back = self.kws_fed - int(end * SAMPLE_RATE)
        return self.history[-back:] if 0 < back <= len(self.history) else np.zeros(0, dtype=np.float32)

    def flush(self):
        """End of input (wav mode): force out any speech still buffered in the VAD."""
        if not self.listening:
            return
        self.vad.flush()
        while not self.vad.empty():
            yield ("command", self.transcribe(self.pop_segment()))

    def transcribe(self, audio: np.ndarray) -> Command:
        t = time.perf_counter()
        s = self.asr.create_stream()
        s.accept_waveform(SAMPLE_RATE, audio)
        self.asr.decode_stream(s)
        return Command(s.result.text.strip(), len(audio) / SAMPLE_RATE, (time.perf_counter() - t) * 1000)


def report(event, payload):
    stamp = time.strftime("%H:%M:%S")
    if event == "wake":
        print(f"[{stamp}] wake word: {payload}  -> listening for a command...", flush=True)
    elif event == "rejected":
        print(f'[{stamp}] spotter fired but verifier heard "{payload}", ignoring', flush=True)
    elif event == "timeout":
        print(f"[{stamp}] no command heard, back to waiting", flush=True)
    elif event == "command":
        print(f'[{stamp}] command: "{payload.text}"  ({payload.audio_seconds:.1f}s audio, ASR {payload.asr_ms:.0f} ms)', flush=True)


def beep():
    import sounddevice as sd

    t = np.linspace(0, 0.12, int(SAMPLE_RATE * 0.12), endpoint=False)
    sd.play((0.2 * np.sin(2 * np.pi * 880 * t)).astype(np.float32), SAMPLE_RATE)


def run_mic(pipe: Pipeline, args):
    import sounddevice as sd

    if args.device is None:
        # Raw ALSA hw devices often reject 16 kHz; the sound server resamples for us.
        names = [d["name"] for d in sd.query_devices()]
        args.device = next((n for n in ("pipewire", "pulse") if n in names), None)
    q: queue.Queue = queue.Queue()
    stream = sd.InputStream(
        channels=1, samplerate=SAMPLE_RATE, dtype="float32", blocksize=CHUNK,
        device=args.device, callback=lambda data, *_: q.put(data[:, 0].copy()),
    )
    if pipe.kws:
        print(f'Say "{args.keyword[0]}" then your command. Ctrl+C to quit.', flush=True)
    else:
        print("Wake word disabled: just speak a command. Ctrl+C to quit.", flush=True)
    with stream:
        while True:
            samples = q.get()
            for event, payload in pipe.feed(samples, time.monotonic()):
                report(event, payload)
                if event == "wake" and not args.no_beep:
                    beep()


def run_wav(pipe: Pipeline, path: str):
    import soundfile as sf

    audio, sr = sf.read(path, dtype="float32", always_2d=True)
    audio = audio[:, 0]
    if sr != SAMPLE_RATE:
        idx = np.linspace(0, len(audio) - 1, int(len(audio) * SAMPLE_RATE / sr))
        audio = np.interp(idx, np.arange(len(audio)), audio).astype(np.float32)
    # Trailing silence lets the VAD close the last segment.
    audio = np.concatenate([audio, np.zeros(SAMPLE_RATE, dtype=np.float32)])
    events = []
    for i in range(0, len(audio), CHUNK):
        now = i / SAMPLE_RATE  # simulated clock so timeouts follow the file, not wall time
        for event, payload in pipe.feed(audio[i : i + CHUNK], now):
            report(event, payload)
            events.append((event, payload))
    for event, payload in pipe.flush():
        report(event, payload)
        events.append((event, payload))
    return events


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--wav", help="run the pipeline on a wav file instead of the mic")
    p.add_argument("--asr", choices=["tiny", "base"], default="base")
    p.add_argument("--verify", choices=["tiny", "base", "none"], default="none",
                   help="ASR model that double-checks each wake word detection")
    p.add_argument("--keyword", action="append", help='wake phrase (repeatable), default "okay edge" + "ok edge"')
    p.add_argument("--kws-threshold", type=float, default=0.05, help="lower = more sensitive, more false wakes")
    p.add_argument("--kws-boost", type=float, default=3.0, help="higher = keyword favoured over other paths")
    p.add_argument("--kws-variants", type=int, default=1, help="number of BPE spellings of the phrase to match")
    p.add_argument("--kws-fp32", action="store_true", help="use fp32 spotter models instead of int8")
    p.add_argument("--no-wake", action="store_true", help="skip wake word, transcribe every utterance")
    p.add_argument("--end-silence", type=float, default=0.6, help="seconds of silence that end a command")
    p.add_argument("--wait-command", type=float, default=5.0, help="seconds to wait for a command after wake")
    p.add_argument("--max-command", type=float, default=10.0)
    p.add_argument("--device", help="input device name/index (see python -m sounddevice)")
    p.add_argument("--no-beep", action="store_true")
    args = p.parse_args()
    args.keyword = args.keyword or ["okay edge", "ok edge"]
    args.verify = None if args.verify == "none" else args.verify

    pipe = Pipeline(args)
    if args.wav:
        run_wav(pipe, args.wav)
    else:
        try:
            run_mic(pipe, args)
        except KeyboardInterrupt:
            print()


if __name__ == "__main__":
    main()
