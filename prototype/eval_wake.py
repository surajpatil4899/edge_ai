"""Score the wake word + ASR pipeline on test_clips/ using manifest.json from make_test_clips.py."""

import argparse
import contextlib
import io
import json
import os
import types

import soundfile as sf

from listen import Pipeline, run_wav

CLIPS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test_clips")


def evaluate(threshold, boost, fp32, asr, verify, details):
    args = types.SimpleNamespace(
        no_wake=False, keyword=["hey edge"], kws_threshold=threshold, kws_boost=boost, kws_variants=1,
        kws_fp32=fp32, end_silence=0.6, wait_command=5.0, max_command=10.0, asr=asr, verify=verify,
    )
    pipe = Pipeline(args)
    with open(os.path.join(CLIPS, "manifest.json")) as f:
        manifest = json.load(f)

    expected = hits = false_wakes = rejected = 0
    negative_seconds = 0.0
    for clip in manifest:
        path = os.path.join(CLIPS, clip["file"])
        pipe.reset()
        with contextlib.redirect_stdout(io.StringIO()):
            events = run_wav(pipe, path)
        wakes = sum(e == "wake" for e, _ in events)
        rejected += sum(e == "rejected" for e, _ in events)
        heard = [p.text for e, p in events if e == "command"]
        want = clip["expected_wakes"]
        expected += want
        hits += min(wakes, want)
        false_wakes += max(0, wakes - want)
        if want == 0:
            negative_seconds += sf.info(path).duration
        if details:
            status = "ok" if wakes == want else ("FALSE WAKE" if wakes > want else "MISS")
            print(f"  {status:10s} {clip['file']:36s} wakes {wakes}/{want}  {' | '.join(heard)}")

    model = ("fp32" if fp32 else "int8") + f" verify={verify or 'none'}"
    print(f"{model} threshold={threshold:<5} boost={boost:<4}  rejected {rejected:<3} wake {hits}/{expected} ({100 * hits / expected:.0f}%)  "
          f"false wakes {false_wakes} in {negative_seconds / 60:.1f} min of non-wake speech", flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--thresholds", type=float, nargs="+", default=[0.1])
    p.add_argument("--boosts", type=float, nargs="+", default=[2.5])
    p.add_argument("--fp32", action="store_true")
    p.add_argument("--asr", choices=["tiny", "base"], default="base")
    p.add_argument("--verify", choices=["tiny", "base", "none"], nargs="+", default=["none"])
    p.add_argument("--details", action="store_true", help="per-clip results and transcripts")
    a = p.parse_args()
    for v in a.verify:
        for b in a.boosts:
            for t in a.thresholds:
                evaluate(t, b, a.fp32, a.asr, None if v == "none" else v, a.details)


if __name__ == "__main__":
    main()
