#!/usr/bin/env bash
# Downloads the on-device speech models into models/.
#   scripts/download_models.sh          runtime models (wake word, VAD, ASR)
#   scripts/download_models.sh --tts    also Piper voices used to synthesize test clips
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/models"
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download
HF=https://huggingface.co
mkdir -p "$DEST"

download() {  # $1 = url, $2 = output path. GitHub release assets can be very slow per connection; aria2 parallelises.
  if command -v aria2c >/dev/null; then
    aria2c -q -c -x16 -s16 -k1M --max-tries=0 --retry-wait=5 -d "$(dirname "$2")" -o "$(basename "$2")" "$1"
  else
    curl -fL --retry 3 --progress-bar -o "$2" "$1"
  fi
}

hf_files() {  # $1 = HF repo, $2 = local dir, rest = files
  local repo=$1 dir=$2; shift 2
  if [[ -d "$dir" ]]; then echo "ok   $(basename "$dir")"; return; fi
  echo "get  $(basename "$dir")"
  mkdir -p "$dir.part"
  for f in "$@"; do
    mkdir -p "$dir.part/$(dirname "$f")"
    curl -fsSL --retry 3 -o "$dir.part/$f" "$HF/$repo/resolve/main/$f"
  done
  mv "$dir.part" "$dir"
}

# Wake word spotter: only published as a GitHub release archive.
KWS=sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
if [[ -d "$DEST/$KWS" ]]; then echo "ok   $KWS"; else
  echo "get  $KWS"
  download "$GH/kws-models/$KWS.tar.bz2" "$DEST/$KWS.tar.bz2"
  tar -xjf "$DEST/$KWS.tar.bz2" -C "$DEST" && rm "$DEST/$KWS.tar.bz2"
fi

if [[ -f "$DEST/silero_vad.onnx" ]]; then echo "ok   silero_vad.onnx"; else
  echo "get  silero_vad.onnx"
  curl -fsSL --retry 3 -o "$DEST/silero_vad.onnx" "$HF/csukuangfj/vad/resolve/main/silero_vad.onnx"
fi

OWW=$DEST/openwakeword
mkdir -p "$OWW"
for f in melspectrogram.onnx embedding_model.onnx; do
  if [[ -f "$OWW/$f" ]]; then echo "ok   openwakeword/$f"; else
    echo "get  openwakeword/$f"; download "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/$f" "$OWW/$f"
  fi
done

AAR=sherpa-onnx-static-link-onnxruntime-2.0.1.aar
LIBS="$ROOT/android/app/libs"
mkdir -p "$LIBS"
if [[ -f "$LIBS/$AAR" ]]; then echo "ok   $AAR"; else
  echo "get  $AAR"; download "$GH/v2.0.1/$AAR" "$LIBS/$AAR"
fi

for size in tiny base; do
  m=sherpa-onnx-moonshine-$size-en-quantized-2026-02-27
  hf_files "csukuangfj2/$m" "$DEST/$m" encoder_model.ort decoder_model_merged.ort tokens.txt LICENSE test_wavs/0.wav
done

if [[ "${1:-}" == "--tts" ]]; then
  for v in lessac-medium ryan-medium hfc_female-medium joe-medium; do
    hf_files "csukuangfj/vits-piper-en_US-$v" "$DEST/vits-piper-en_US-$v" \
      "en_US-$v.onnx" "en_US-$v.onnx.json" tokens.txt MODEL_CARD
  done
fi

echo
du -sh "$DEST"/* | sort -h
