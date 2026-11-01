#!/usr/bin/env bash
# Install the debug app on the connected phone and make MODEL the Chat tab's model.
#   scripts/deploy_phone.sh [models/llm/<model>.gguf]
# Older .gguf files in the app's llm/ folder are moved to llm/base/ (kept for comparison; the app
# only loads top-level files).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb
MODEL=${1:-$ROOT/models/llm/functiongemma-edge-v1-q8_0.gguf}
APK=$ROOT/android/app/build/outputs/apk/debug/app-debug.apk
DIR=/sdcard/Android/data/com.edgeai.assistant/files/llm

echo "waiting for a USB-connected phone with USB debugging on..."
"$ADB" wait-for-usb-device
SERIAL=$("$ADB" devices | awk '$2=="device" && $1 !~ /^emulator/ {print $1; exit}')
echo "phone: $SERIAL ($("$ADB" -s "$SERIAL" shell getprop ro.product.model))"

"$ADB" -s "$SERIAL" install -r -g "$APK" | tail -1
"$ADB" -s "$SERIAL" shell "mkdir -p $DIR/base && for f in $DIR/*.gguf; do [ -e \"\$f\" ] && [ \"\$(basename \$f)\" != \"$(basename "$MODEL")\" ] && mv \"\$f\" $DIR/base/; done; true"
"$ADB" -s "$SERIAL" push "$MODEL" "$DIR/" | tail -1
"$ADB" -s "$SERIAL" shell "ls -l $DIR $DIR/base"
echo "deployed $(basename "$MODEL")"
