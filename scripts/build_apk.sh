#!/bin/bash
set -euo pipefail

# Build debug APK for Hermes Android Bridge
# Requires: JDK 17, Android SDK (cmdline-tools)

cd "$(dirname "$0")/.."

echo "=== Building Hermes Android Bridge ==="
echo "Project dir: $(pwd)"

# Check Java
if ! command -v java &>/dev/null; then
    echo "ERROR: Java not found. Install JDK 17 first."
    exit 1
fi

echo "Java version:"
java -version 2>&1 | head -1

# Check ANDROID_HOME
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    echo "WARNING: ANDROID_HOME not set. Trying /opt/android-sdk..."
    export ANDROID_HOME=/opt/android-sdk
fi

if [ ! -d "$ANDROID_HOME" ]; then
    echo "ERROR: Android SDK not found at $ANDROID_HOME"
    echo "Install Android command-line-tools or set ANDROID_HOME"
    echo "  sdkmanager --list"
    echo "  sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\""
    exit 1
fi

echo "Android SDK: $ANDROID_HOME"

# Build
echo ""
echo "--- Running Gradle assembleDebug ---"
./gradlew assembleDebug 2>&1 | tail -50

# Check output
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    echo ""
    echo "=== BUILD SUCCESSFUL ==="
    echo "APK: $(pwd)/$APK_PATH"
    echo "Size: $(du -h "$APK_PATH" | cut -f1)"
    echo ""
    echo "Install with:"
    echo "  adb install $APK_PATH"
else
    echo ""
    echo "=== BUILD FAILED ==="
    echo "APK not found at expected path: $APK_PATH"
    exit 1
fi
