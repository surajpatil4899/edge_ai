#!/usr/bin/env bash
# Install the debug app (which bundles the command model) on the connected phone.
#   scripts/deploy_phone.sh                 install; move side-loaded models aside so the bundled one is used
#   scripts/deploy_phone.sh model.gguf      also side-load model.gguf, which overrides the bundled model
# Side-loaded models live in the app's external llm/ folder; moved ones go to llm/base/ (ignored by the app).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb
MODEL=${1:-}
APK=$ROOT/android/app/build/outputs/apk/debug/app-debug.apk
DIR=/sdcard/Android/data/com.edgeai.assistant/files/llm

echo "waiting for a USB-connected phone with USB debugging on..."
"$ADB" wait-for-usb-device
SERIAL=$("$ADB" devices | awk '$2=="device" && $1 !~ /^emulator/ {print $1; exit}')
echo "phone: $SERIAL ($("$ADB" -s "$SERIAL" shell getprop ro.product.model))"

"$ADB" -s "$SERIAL" install -r -g "$APK" | tail -1
KEEP=$([[ -n "$MODEL" ]] && basename "$MODEL" || echo "")
"$ADB" -s "$SERIAL" shell "mkdir -p $DIR/base && for f in $DIR/*.gguf; do [ -e \"\$f\" ] && [ \"\$(basename \$f)\" != \"$KEEP\" ] && mv \"\$f\" $DIR/base/; done; true"
if [[ -n "$MODEL" ]]; then
  "$ADB" -s "$SERIAL" push "$MODEL" "$DIR/" | tail -1
  echo "side-loaded $(basename "$MODEL") (overrides the bundled model)"
else
  echo "using the bundled model"
fi
"$ADB" -s "$SERIAL" shell "ls -l $DIR $DIR/base"
