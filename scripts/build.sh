#!/usr/bin/env bash
# Powered by NyphexAI, Developed by Hikaru(i@rua.moe)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ -n "${ANDROID_HOME:-}" ]; then
  SDK_DIR="$ANDROID_HOME"
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  SDK_DIR="$ANDROID_SDK_ROOT"
elif [ -d "$HOME/Library/Android/sdk" ]; then
  SDK_DIR="$HOME/Library/Android/sdk"
else
  SDK_DIR="$HOME/Android/Sdk"
fi

BUILD_TOOLS_VER="$(ls -1 "$SDK_DIR/build-tools" | sort -V | tail -n 1)"
BUILD_TOOLS="$SDK_DIR/build-tools/$BUILD_TOOLS_VER"
ANDROID_JAR="$SDK_DIR/platforms/android-34/android.jar"

if [ ! -x "$BUILD_TOOLS/aapt2" ]; then
  echo "ERROR: Android build-tools not found under $SDK_DIR/build-tools" >&2
  exit 1
fi
if [ ! -f "$ANDROID_JAR" ]; then
  echo "ERROR: Android SDK platform android-34 not found at $ANDROID_JAR" >&2
  exit 1
fi

BUILD_DIR="$ROOT_DIR/build"
KEYSTORE_DIR="$ROOT_DIR/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/sonycam.jks"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$KEYSTORE_DIR"

echo "[1/6] Compiling resources with aapt2..."
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT_DIR/app/src/main/res" -o "$BUILD_DIR/res.zip"

echo "[2/6] Linking resources and manifest..."
"$BUILD_TOOLS/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT_DIR/app/src/main/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  -o "$BUILD_DIR/base.apk" \
  --auto-add-overlay \
  "$BUILD_DIR/res.zip"

echo "[3/6] Compiling Java sources..."
JAVA_FILES=()
while IFS= read -r f; do
  JAVA_FILES+=("$f")
done < <(find "$ROOT_DIR/app/src/main/java" "$BUILD_DIR/gen" -name '*.java' | sort)

javac \
  -encoding UTF-8 \
  -source 11 \
  -target 11 \
  -Xlint:-options \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  "${JAVA_FILES[@]}"

echo "[4/6] Converting bytecode to DEX with d8..."
jar cf "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
"$BUILD_TOOLS/d8" \
  --min-api 29 \
  --lib "$ANDROID_JAR" \
  --output "$BUILD_DIR/dex" \
  "$BUILD_DIR/classes.jar"

echo "[5/6] Packaging and aligning APK..."
cp "$BUILD_DIR/base.apk" "$BUILD_DIR/unaligned.apk"
(cd "$BUILD_DIR/dex" && zip -q -u "$BUILD_DIR/unaligned.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

if [ ! -f "$KEYSTORE_FILE" ]; then
  echo "[*] Generating local signing keystore..."
  keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_FILE" \
    -storepass sonycamguard \
    -keypass sonycamguard \
    -alias sonycam \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=BraviaCam Guard, OU=LocalSecurity, O=SONY_CAM, L=Local, ST=Local, C=JP"
fi

echo "[6/6] Signing and verifying APK..."
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE_FILE" \
  --ks-pass pass:sonycamguard \
  --key-pass pass:sonycamguard \
  --ks-key-alias sonycam \
  --out "$BUILD_DIR/sonycam-guard.apk" \
  "$BUILD_DIR/aligned.apk"

"$BUILD_TOOLS/apksigner" verify "$BUILD_DIR/sonycam-guard.apk"
ls -lh "$BUILD_DIR/sonycam-guard.apk"
echo "BUILD SUCCEEDED: $BUILD_DIR/sonycam-guard.apk"
