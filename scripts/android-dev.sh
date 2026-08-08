#!/usr/bin/env bash
#
# Run seq on Android in dev mode (hot-reload frontend, no dev server on device).
#
# Handles both targets:
#   - Real phone over USB : uses `adb reverse` so the app hits the host Vite dev
#                           server at localhost:1420 (no --host override needed).
#   - Waydroid container  : uses the bridge gateway IP 192.168.240.1.
#
# Usage:
#   scripts/android-dev.sh            # auto-detect device
#   scripts/android-dev.sh --phone    # force phone path
#   scripts/android-dev.sh --waydroid # force waydroid path
#
# Depends on: adb, ANDROID_HOME, NDK (set in package.json scripts).

set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/home/rp/android-sdk/jdk-21}"
export ANDROID_HOME="${ANDROID_HOME:-/home/rp/android-sdk}"
export NDK_HOME="${NDK_HOME:-$ANDROID_HOME/ndk/28.1.13356709}"
export PATH="$PATH:$ANDROID_HOME/platform-tools"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_PROPS="$ROOT/src-tauri/gen/android/gradle.properties"
DEV_PORT=1420

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
log()  { printf '\033[1;34m[android-dev]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[android-dev]\033[0m %s\n' "$*"; }

set_gradle_abi() { # arch -> abi -> rust-target ; writes gradle.properties
  local arch="$1" abi="$2" target="$3"
  sed -i.bak \
    -e "s/^abiList=.*/abiList=$abi/" \
    -e "s/^archList=.*/archList=$arch/" \
    -e "s/^targetList=.*/targetList=$target/" \
    "$GRADLE_PROPS"
  rm -f "$GRADLE_PROPS.bak"
}

pick_waydroid_host() {
  # Prefer the waydroid0 bridge gateway; fall back to the first Network: line from vite.
  local gw
  gw="$(ip -4 addr show waydroid0 2>/dev/null | sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p' | head -1)"
  echo "${gw:-192.168.240.1}"
}

# Force the generated android asset config to use the given devUrl. tauri only rewrites
# gen/android/app/src/main/assets/tauri.conf.json when re-initing, so a stale host (from a
# previous --host run) persists across target/ cleans and gets baked into the .so →
# "Failed to request http://<stale-host>:1420/".
fix_generated_devurl() { # devUrl
  local devUrl="$1"
  local f="$ROOT/src-tauri/gen/android/app/src/main/assets/tauri.conf.json"
  if [[ -f "$f" ]] && grep -q 'devUrl' "$f"; then
    python3 - "$f" "$devUrl" <<'PY'
import json, sys
f, url = sys.argv[1], sys.argv[2]
d = json.load(open(f))
if d.get("build", {}).get("devUrl") != url:
    d["build"]["devUrl"] = url
    json.dump(d, open(f, "w"), indent=2)
    print(f"[android-dev] fixed generated devUrl -> {url}")
else:
    print(f"[android-dev] generated devUrl already {url}")
PY
  fi
}

# ---------------------------------------------------------------------------
# device selection
# ---------------------------------------------------------------------------
# Normalize: accept `--phone`, `phone`, `--waydroid`, `waydroid`, or nothing (auto).
MODE="${1#--}"
MODE="${MODE:-auto}"

if [[ "$MODE" == "auto" ]]; then
  # A real device over USB (serial != empty, transport usb) -> phone; else waydroid.
  if adb devices -l 2>/dev/null | awk 'NR>1 && /device/{print}' | grep -q 'usb:' ; then
    MODE=phone
  elif adb devices -l 2>/dev/null | awk 'NR>1 && /device/{print}' | grep -qE '192\.168\.' ; then
    MODE=waydroid
  else
    warn "No device detected; assuming Waydroid."
    MODE=waydroid
  fi
fi

# ---------------------------------------------------------------------------
# run
# ---------------------------------------------------------------------------
case "$MODE" in
  phone)
    ABI="$(adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
    case "$ABI" in
      arm64-v8a)   set_gradle_abi arm64   arm64-v8a aarch64; RTARGET=aarch64-linux-android ;;
      x86_64)      set_gradle_abi x86_64  x86_64    x86_64  ; RTARGET=x86_64-linux-android ;;
      armeabi-v7a) set_gradle_abi arm     armeabi-v7a armv7  ; RTARGET=armv7-linux-androideabi ;;
      *)           warn "Unknown ABI $ABI; defaulting to arm64-v8a."
                   set_gradle_abi arm64 arm64-v8a aarch64; RTARGET=aarch64-linux-android ;;
    esac
    # Phone uses adb reverse → devUrl must be localhost:1420.
    fix_generated_devurl "http://localhost:$DEV_PORT/"

    log "Phone detected (ABI=$ABI). Forwarding port $DEV_PORT via adb reverse."
    adb reverse tcp:$DEV_PORT tcp:$DEV_PORT
    adb reverse --list

    log "Starting dev build for device… (Ctrl-C to stop)"
    # Explicit --host 127.0.0.1: tauri android dev auto-detects a "public" address
    # (e.g. 172.16.0.2 from warp VPN) and bakes it into the app, breaking adb reverse.
    # Pin to loopback: the phone reaches host via `adb reverse tcp:1420`, so devUrl
    # must stay on 127.0.0.1:1420.
    cd "$ROOT"
    exec "$ROOT/node_modules/.bin/tauri" android dev --host 127.0.0.1
    ;;
  waydroid)
    HOST="$(pick_waydroid_host)"
    set_gradle_abi x86_64 x86_64 x86_64
    fix_generated_devurl "http://$HOST:$DEV_PORT/"

    log "Waydroid detected. Dev server on $HOST:$DEV_PORT (bridge gateway)."
    cd "$ROOT"
    exec "$ROOT/node_modules/.bin/tauri" android dev --host "$HOST"
    ;;
  *)
    echo "Unknown mode: $MODE" >&2
    exit 2
    ;;
esac
