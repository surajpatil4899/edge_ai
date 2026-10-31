"""Compare candidate wake phrases: recall on synthesized "<phrase>. <command>" clips across voices,
and false wakes on ordinary speech that never contains the phrase.

  eval_phrases.py "hey edge" "hey computer" ...
"""

import argparse
import contextlib
import io
import os
import types

import numpy as np

from listen import Pipeline, run_wav
from make_test_clips import CHATTER, COMMANDS, MODELS, NEGATIVES, SR, speak, tts_for

VOICES = ["lessac-medium", "ryan-medium", "hfc_female-medium", "joe-medium"]
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "phrase_clips")


def silence(seconds):
    return np.zeros(int(seconds * SR), dtype=np.float32)


def synthesize(phrases):
    os.makedirs(OUT, exist_ok=True)
    for voice in VOICES:
        if not os.path.isdir(os.path.join(MODELS, f"vits-piper-en_US-{voice}")):
            continue
        tts, short = tts_for(voice), voice.split("-")[0]
        neg = os.path.join(OUT, f"neg_{short}.wav")
        if not os.path.exists(neg):
            parts = [silence(0.5)]
            for text in NEGATIVES + CHATTER:
                parts += [speak(tts, text), silence(0.8)]
            import soundfile as sf

            sf.write(neg, np.concatenate(parts), SR)
        for phrase in dict.fromkeys(p.split("|")[0] for p in phrases):
            for key, cmd in COMMANDS.items():
                path = os.path.join(OUT, f"pos_{phrase.replace(' ', '_')}_{key}_{short}.wav")
                if os.path.exists(path):
                    continue
                import soundfile as sf

                clip = [silence(0.5), speak(tts, phrase.title() + "."), silence(0.4), speak(tts, cmd), silence(1.0)]
                sf.write(path, np.concatenate(clip), SR)


def evaluate(phrase, boost, threshold):
    spoken = phrase.split("|")[0]  # alternatives after "|" are extra spellings the spotter accepts
    args = types.SimpleNamespace(no_wake=False, keyword=phrase.split("|"), kws_threshold=threshold, kws_boost=boost,
                                 kws_variants=1, end_silence=0.8, wait_command=6.0, max_command=10.0, asr="tiny")
    pipe = Pipeline(args)
    hits = total = false = 0
    neg_seconds = 0.0
    for name in sorted(os.listdir(OUT)):
        path = os.path.join(OUT, name)
        is_pos = name.startswith(f"pos_{spoken.replace(' ', '_')}_")
        if not (is_pos or name.startswith(("neg_", "conf_"))):
            continue
        pipe.reset()
        with contextlib.redirect_stdout(io.StringIO()):
            events = run_wav(pipe, path)
        wakes = sum(e == "wake" for e, _ in events)
        if is_pos:
            total += 1
            hits += wakes > 0
        else:
            false += wakes
            import soundfile as sf

            neg_seconds += sf.info(path).duration
    return hits, total, false, neg_seconds


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("phrases", nargs="+")
    p.add_argument("--settings", default="2.5:0.1,1.5:0.25", help="boost:threshold pairs")
    a = p.parse_args()
    synthesize(a.phrases)
    settings = [tuple(map(float, s.split(":"))) for s in a.settings.split(",")]
    print(f"{'phrase':20s} " + "  ".join(f"{'boost ' + str(b) + ' thr ' + str(t):>30s}" for b, t in settings))
    for phrase in a.phrases:
        row = []
        for b, t in settings:
            hits, total, false, neg = evaluate(phrase, b, t)
            row.append(f"wake {hits:2d}/{total} ({100 * hits / max(total, 1):3.0f}%) false {false:2d}")
        print(f"{phrase:20s} " + "  ".join(f"{r:>30s}" for r in row), flush=True)
    print(f"(false wakes counted over {neg / 60:.1f} min of ordinary speech, 4 voices)")


if __name__ == "__main__":
    main()
