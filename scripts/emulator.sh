#!/usr/bin/env bash
# Start (or cold-restart with --restart) the edge_pixel emulator with host-mic access,
# wait for boot, and turn off its network so it doesn't burn hotspot data on updates.
set -euo pipefail

SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
ADB=$SDK/platform-tools/adb
LOG=${XDG_CACHE_HOME:-$HOME/.cache}/edge_ai/emulator.log
mkdir -p "$(dirname "$LOG")"

running() { [[ -n "$(pgrep -x qemu-system-x86 2>/dev/null; pgrep -f '^[^ ]*qemu-system-x86_64 -avd' 2>/dev/null)" ]]; }

if [[ "${1:-}" == "--restart" ]] && running; then
  echo "stopping emulator..."
  "$ADB" emu kill >/dev/null 2>&1 || true
  for _ in $(seq 60); do running || break; sleep 1; done
fi

if ! running; then
  echo "starting emulator..."
  # -no-snapshot-load: a cold boot; restoring a snapshot can bring back a stuck audio-input state.
  (cd ~ && setsid nohup "$SDK/emulator/emulator" -avd edge_pixel -allow-host-audio -gpu auto \
     -no-boot-anim -no-snapshot-load >"$LOG" 2>&1 </dev/null &)
fi

timeout 300 "$ADB" wait-for-device
until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done
"$ADB" shell svc wifi disable
"$ADB" shell svc data disable
echo "emulator ready"
