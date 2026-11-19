#!/usr/bin/env bash
# Powered by NyphexAI, Developed by Hikaru(i@rua.moe)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="/opt/homebrew/bin/adb"
APK="$ROOT_DIR/build/sonycam-guard.apk"
PKG="com.sonycam.guard"
TOKEN_FILE="$ROOT_DIR/.token"

if [ ! -f "$APK" ]; then
  echo "APK not found at $APK; run scripts/build.sh first." >&2
  exit 1
fi

TARGET="${1:-}"
if [ -z "$TARGET" ]; then
  TARGET="$("$ADB" devices | awk '$2=="device" && $1 ~ /^10\.0\.40\.105:/ {print $1; exit}')"
fi

if [ -z "$TARGET" ]; then
  echo "Resolving TV ADB port via Bonjour..."
  PORT="$(perl -e 'alarm 3; exec @ARGV' dns-sd -L "adb-126479003000549B0000AFEAE17-iVLgAC" _adb-tls-connect._tcp local. 2>/dev/null | sed -n 's/.*Android-2\.local\.:\([0-9]*\).*/\1/p' | head -n 1 || true)"
  if [ -z "$PORT" ]; then
    echo "Could not resolve TV ADB port. Pass host:port as first argument." >&2
    exit 1
  fi
  TARGET="10.0.40.105:$PORT"
  "$ADB" connect "$TARGET"
fi

echo "Using ADB target: $TARGET"

if [ ! -f "$TOKEN_FILE" ]; then
  openssl rand -hex 24 > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"
fi
TOKEN="$(tr -d '[:space:]' < "$TOKEN_FILE")"

echo "[1/4] Installing APK..."
"$ADB" -s "$TARGET" install -r "$APK"

echo "[2/4] Granting CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS, SAW, and Doze exemption..."
"$ADB" -s "$TARGET" shell pm grant "$PKG" android.permission.CAMERA
"$ADB" -s "$TARGET" shell pm grant "$PKG" android.permission.RECORD_AUDIO
"$ADB" -s "$TARGET" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
"$ADB" -s "$TARGET" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
"$ADB" -s "$TARGET" shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true

echo "[3/4] Auditing granted permissions and AppOps on device..."
"$ADB" -s "$TARGET" shell dumpsys package "$PKG" | grep -E "android.permission\.(CAMERA|RECORD_AUDIO): granted=" | head -n 6
"$ADB" -s "$TARGET" shell appops get "$PKG" SYSTEM_ALERT_WINDOW

echo "[4/4] Launching BraviaCam Guard MainActivity in auto_start lock mode..."
"$ADB" -s "$TARGET" shell am start \
  -n "$PKG/.MainActivity" \
  --ez auto_start true \
  --es bootstrap_token "$TOKEN"

echo "DEPLOYED. Waiting 4s for Camera2 initialization..."
sleep 4
"$ADB" -s "$TARGET" shell dumpsys media.camera | head -n 25
