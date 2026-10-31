# edge_ai

On-device voice assistant for Android: wake word ("**Okay Edge**") → speech recognition → (next) a
small fine-tuned LLM that turns commands into phone actions (alarms, reminders, texts, calls). Every
model runs on the phone; no audio leaves the device.

## Status

| Part | What | State |
|---|---|---|
| 1 | Wake word + VAD + ASR | ✅ Android app working on a Galaxy S25 Ultra; personal wake model |
| 2 | Fine-tune Gemma (FunctionGemma) for command → function call | next |
| 3 | Action executor (alarm, timer, reminder, call, SMS, …) | later |

## How it works

```
mic (16 kHz) ─┬─> personal wake model ─── "Okay Edge" ──> beep, start listening
              │   (openWakeWord features + MLP trained on your voice; falls back to a generic
              │    keyword spotter when no personal model is bundled)
              └─> Silero VAD ──> one utterance ──> Moonshine ASR ──> strip "Okay Edge" / fillers
                                                                     ──> command text (to the LLM, next)
```

- After a wake the app **keeps listening until a real command arrives**: fragments that are only the
  wake phrase's tail, the beep or "um" are ignored; it waits up to 6 s (15 s max) for the command.
- Commands can follow the wake phrase without a pause ("Okay Edge set an alarm…").
- Everything runs in a foreground service (microphone type), so it works with the screen off.

### Models

| Stage | Model | Size |
|---|---|---|
| Wake word (personal) | openWakeWord `melspectrogram.onnx` + `embedding_model.onnx` (v0.5.1) + a ~2 MB MLP trained by `train_wakeword.py` | 4.5 MB |
| Wake word (fallback) | sherpa-onnx `kws-zipformer-gigaspeech-3.3M` int8, open vocabulary | 5 MB |
| VAD | Silero VAD | 2 MB |
| ASR | Moonshine base, English, quantized (2026-02-27), via sherpa-onnx 2.0.1 | 135 MB |

Runtimes: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 2.0.1 (static-link AAR) and
ONNX Runtime Android 1.28.0.

## Results on real phone audio

Wake phrase, generic detectors vs. a personal model, on recordings from the phone (speaker with an
Indian-English accent, who pronounces "Edge" close to "age"):

| Detector | Wake attempts caught | False wakes |
|---|---|---|
| "Hey Edge", generic spotter | ~3 / 10 | — |
| "Okay Edge", generic spotter | 4–5 / 6 | 2 in 80 min |
| **"Okay Edge", personal model** (held-out audio) | **12 / 12** | **0 in 13 min** |

Personal model detection rule: score > 0.8 for 3 consecutive 80 ms steps, and the last 1.3 s louder
than −35 dBFS (far-away TV/background speech sits around −40 dBFS).

On synthetic voices (`eval_phrases.py`, 4 Piper voices × 6 commands, 6 min of ordinary + confuser
speech) "Hey Edge" scored 83% with 4 false wakes, "Okay Edge" 100% with 0, which is why the phrase changed.

Lessons that shaped the pipeline:
- The generic spotter reports the keyword ~0.5 s after it ends, so command audio is replayed from the
  keyword's end; Silero VAD segments are padded 200 ms backwards so plosives survive.
- An off-the-shelf wake word can't adapt to an accent or a made-up word ("Hello Edgio" scored 0/6 on
  the real voice). A small classifier on pre-trained speech embeddings, trained on ~30 of the user's
  own samples, fixes that in minutes.
- Training features must be computed **exactly** the way the phone streams them (openWakeWord's mel
  model even mixes data across a batch); `train_wakeword.py` reproduces the app's 80 ms streaming
  bit-for-bit.
- Negatives must include the user's own non-wake speech, built the same way as positives, or the
  model learns "this person is talking" instead of the phrase.

## Layout

```
scripts/
  download_models.sh     fetch models + the sherpa-onnx static AAR (all gitignored)
  emulator.sh            start / --restart the emulator (host mic on, network off)
prototype/               Python: desktop pipeline, evaluation and training
  listen.py              live mic (or --wav) pipeline with the generic spotter
  train_wakeword.py      train the personal wake model -> android/app/src/main/assets/wakeword.json
  eval_session.py        analyse a phone session recording: levels, wake hits, ASR per utterance
  eval_phrases.py        compare candidate wake phrases on synthetic voices
  eval_wake.py           wake recall / false wakes on synthetic test clips
  make_test_clips.py     synthesize test clips with Piper TTS
  mic_bridge.py          stream laptop mic (or wav files) into the app over adb (debug builds)
  emulator_mic.py        control the emulator's microphone over its gRPC API
android/                 Kotlin + Jetpack Compose app
  VoicePipeline.kt       wake word -> VAD -> ASR -> command text
  WakeWordModel.kt       personal wake model (ONNX Runtime + MLP head)
  ListenerService.kt     foreground service owning the mic
  Enrollment.kt          record wake / not-wake samples for training
  SessionRecorder.kt     debug: save everything heard, for offline tuning
```

## Setup (Arch Linux)

- Android Studio (AUR `android-studio`), SDK at `~/Android/Sdk`: platforms 36 and 37.2, build-tools
  36.1.0, emulator, `system-images;android-36;google_apis;x86_64`. JDK: Android Studio's bundled JBR.
- `aria2` (GitHub release downloads can be very slow; the scripts use parallel connections),
  `espeak-ng` (only for synthetic test clips), `android-udev` (USB access to a phone).
- Shell profile:
  ```sh
  export ANDROID_HOME=$HOME/Android/Sdk
  export JAVA_HOME=/opt/android-studio/jbr
  export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
  ```

```sh
scripts/download_models.sh            # runtime models + android/app/libs AAR
scripts/download_models.sh --tts      # + Piper voices for synthetic clips

cd prototype
python -m venv .venv && .venv/bin/pip install -r requirements.txt
```

## Build and run

```sh
cd android
./gradlew testDebugUnitTest installDebug   # copies models from ../models into the APK
```

Without `assets/wakeword.json` (it is personal, so not committed) the app uses the generic spotter.

### Train your personal wake model

1. In the app (debug build): tap **Stop**, then in the *Wake word samples* card tap
   **Record "Okay Edge" sample** ~30 times, following the on-screen hints (in hand, arm's length,
   varied). Optionally record ~15 **NOT-wake** samples ("Okay", "Okay, got it", "Hey Edge", …).
   Samples are also saved to the phone's `Download/EdgeAI/enroll`.
2. Optionally record a normal session with **Save session audio** on (more negatives, and held-out
   attempts for evaluation).
3. Pull and train:
   ```sh
   adb pull /sdcard/Android/data/com.edgeai.assistant/files/enroll/. prototype/enroll/
   adb pull /sdcard/Android/data/com.edgeai.assistant/files/recordings/. prototype/recordings/
   cd prototype && .venv/bin/python train_wakeword.py      # ~2 min on CPU; prints held-out results
   cd ../android && ./gradlew installDebug
   ```
   The session file names and wake-attempt times used for evaluation are listed at the top of
   `train_wakeword.py`.

### Testing on the emulator

The emulator's host-mic passthrough stops delivering audio once the app reopens the mic, so debug
builds have a **laptop mic bridge** over `adb`:

```sh
scripts/emulator.sh
cd prototype
.venv/bin/python mic_bridge.py                                        # live laptop mic
.venv/bin/python mic_bridge.py --wav test_clips/pos_alarm_joe.wav     # repeatable input
```

In the app switch on **Use laptop mic (adb bridge)**, then **Start listening**. On a real phone leave
it off.

### Samsung phones

If adb keeps losing the phone, turn off **Settings → Security and privacy → Auto Blocker**; it
switches USB debugging off whenever the cable reconnects.

## Known issues / next steps

- Moonshine sometimes drops softly spoken words and hallucinates repeated sentences on far-away
  background speech; Canary 180M was more accurate on the user's commands (~3× slower) and is the
  candidate replacement.
- The personal model has not yet seen plain "okay" from the user; NOT-wake samples will cover that.
- Next part: fine-tune FunctionGemma 270M to map commands to function calls, then the action executor.
