"""Synthesize wake-word test clips with Piper TTS so the pipeline can be tested without a mic."""

import json
import os
import subprocess

import numpy as np
import sherpa_onnx
import soundfile as sf

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS = os.path.join(ROOT, "models")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test_clips")
SR = 16000
WAKE = "Hey Edge."

VOICES = ["lessac-medium", "ryan-medium", "hfc_female-medium", "joe-medium"]

COMMANDS = {
    "alarm": "Set an alarm for seven thirty tomorrow morning.",
    "call": "Call mom.",
    "text": "Send a text to Rahul saying I will be late.",
    "timer": "Set a timer for ten minutes.",
    "remind": "Remind me to buy milk at six pm.",
    "torch": "Turn on the flashlight.",
}

# Phrases that sound close to the wake word or are other assistants' wake words.
NEGATIVES = [
    "Hey Ed, how are you doing today?",
    "The hedge in the garden needs trimming.",
    "That is an edge case we should handle.",
    "Hey Eddie, pass me the salt.",
    "I'm a bit on edge about the exam.",
    "He was standing on the ledge.",
    "Hey Siri, what's the weather like?",
    "Okay Google, set a timer.",
    "Hey Alexa, play some music.",
    "Hey judge, may I approach the bench?",
    "Hey everyone, welcome to the meeting.",
    "We need an edge over our competitors.",
    "This is a cutting edge device.",
    "Hey Reggie, come over here.",
    "The age of the universe is very large.",
    "Hey, did you get my message?",
    "Hand me the sledge hammer.",
]

CHATTER = [
    "I was thinking about dinner.",
    "The traffic was terrible this morning.",
    "Did you watch the match last night?",
]


def clips():
    """Yields (name, segments, expected_wakes, commands); a float segment is extra silence in seconds."""
    for key, cmd in COMMANDS.items():
        yield f"pos_{key}", [WAKE, cmd], 1, [cmd]
    # Fast talkers don't pause after the wake word.
    yield "pos_nopause_alarm", ["Hey Edge, set an alarm for six am."], 1, ["set an alarm for six am."]
    yield "pos_chatter_then_wake", [CHATTER[0], WAKE, COMMANDS["remind"]], 1, [COMMANDS["remind"]]
    for i, text in enumerate(NEGATIVES):
        yield f"neg_{i:02d}", [text], 0, []
    # Long runs exercise the streaming state the way a real session does.
    yield "session_no_wake", NEGATIVES + CHATTER, 0, []
    session, cmds = [], []
    for chat, key in zip(CHATTER, ["alarm", "text", "torch"]):
        # People pause after giving a command before chatting again.
        session += [chat, NEGATIVES[len(cmds) * 3], WAKE, COMMANDS[key], 1.5]
        cmds.append(COMMANDS[key])
    yield "session_three_commands", session, 3, cmds


def tts_for(voice: str) -> sherpa_onnx.OfflineTts:
    d = os.path.join(MODELS, f"vits-piper-en_US-{voice}")
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=os.path.join(d, f"en_US-{voice}.onnx"),
                tokens=os.path.join(d, "tokens.txt"),
            ),
            num_threads=4,
        )
    )
    return sherpa_onnx.OfflineTts(cfg)


def phonemize(text: str) -> str:
    # sherpa-onnx 2.x has no built-in phonemizer for Piper voices, so use espeak-ng (dev-only).
    ipa = subprocess.run(["espeak-ng", "-q", "--ipa", "-v", "en-us", text], capture_output=True, text=True, check=True)
    end = text.rstrip()[-1:]
    return " ".join(ipa.stdout.split()) + (end if end in ".,?!" else "")


def speak(tts: sherpa_onnx.OfflineTts, text: str) -> np.ndarray:
    cfg = sherpa_onnx.GenerationConfig()
    cfg.sid = 0
    cfg.speed = 1.0
    cfg.phoneme_codepoints = [[ord(c) for c in phonemize(text)]]
    g = tts.generate(text, cfg)
    audio = np.array(g.samples, dtype=np.float32)
    idx = np.linspace(0, len(audio) - 1, int(len(audio) * SR / g.sample_rate))
    return np.interp(idx, np.arange(len(audio)), audio).astype(np.float32)


def main():
    os.makedirs(OUT, exist_ok=True)
    rng = np.random.default_rng(0)
    manifest = []
    for voice in VOICES:
        if not os.path.isdir(os.path.join(MODELS, f"vits-piper-en_US-{voice}")):
            print("skip voice", voice)
            continue
        tts = tts_for(voice)
        short = voice.split("-")[0]
        for name, segments, wakes, commands in clips():
            parts = [np.zeros(int(0.5 * SR), dtype=np.float32)]
            for seg in segments:
                if isinstance(seg, float):  # explicit extra pause
                    parts.append(np.zeros(int(seg * SR), dtype=np.float32))
                    continue
                # Natural pause length varies; 0.25-0.6 s between phrases.
                parts += [speak(tts, seg), np.zeros(int(rng.uniform(0.25, 0.6) * SR), dtype=np.float32)]
            parts.append(np.zeros(int(0.5 * SR), dtype=np.float32))
            file = f"{name}_{short}.wav"
            sf.write(os.path.join(OUT, file), np.concatenate(parts), SR)
            manifest.append({"file": file, "voice": short, "expected_wakes": wakes, "commands": commands})
        print("voice", voice, "done")
    with open(os.path.join(OUT, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1)
    print(len(manifest), "clips ->", os.path.relpath(OUT, ROOT))


if __name__ == "__main__":
    main()
