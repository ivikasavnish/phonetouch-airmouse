#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/home/vikasavn/phonetouch"
ANDROID_DIR="$PROJECT_DIR/android"
BUILD_DIR="$ANDROID_DIR/build"

SDK_DIR="/home/vikasavn/Android/Sdk"
BUILD_TOOLS="$SDK_DIR/build-tools/36.0.0"
PLATFORM_JAR="$SDK_DIR/platforms/android-35/android.jar"
ADB="$SDK_DIR/platform-tools/adb"

echo "[1/5] Preparing clean build directories..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex"

echo "[2/5] Compiling Java sources against Android SDK 35..."
javac --release 11 \
  -cp "$PLATFORM_JAR" \
  -d "$BUILD_DIR/classes" \
  "$ANDROID_DIR/src/com/phonetouch/app/MainActivity.java"

echo "[3/5] Converting JVM bytecode to Dalvik Executable (DEX)..."
"$BUILD_TOOLS/d8" \
  --lib "$PLATFORM_JAR" \
  --min-api 24 \
  --output "$BUILD_DIR/dex" \
  $(find "$BUILD_DIR/classes" -name "*.class")

echo "[4/5] Packaging and aligning APK..."
"$BUILD_TOOLS/aapt2" link \
  -o "$BUILD_DIR/unaligned.apk" \
  --manifest "$ANDROID_DIR/AndroidManifest.xml" \
  -I "$PLATFORM_JAR"

# Add classes.dex to root of APK zip
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/unaligned.apk" classes.dex)

"$BUILD_TOOLS/zipalign" -f -p 4 \
  "$BUILD_DIR/unaligned.apk" \
  "$BUILD_DIR/aligned.apk"

echo "[5/5] Signing APK and installing onto connected phone..."
KEYSTORE="$ANDROID_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

APK_OUT="$PROJECT_DIR/PhoneTouch.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$APK_OUT" \
  "$BUILD_DIR/aligned.apk"

echo "[+] Built signed native APK: $APK_OUT"

"$ADB" reverse tcp:9999 tcp:9999
"$ADB" install -r "$APK_OUT"
"$ADB" shell am start -n com.phonetouch.app/.MainActivity

echo "[+] PhoneTouch AirMouse installed and launched on your Android phone!"
