#!/usr/bin/env bash
# Builds the debug APK, runs the JVM unit tests, and copies the APK to dist/mu-debug.apk (gitignored).
# Needs JDK 17+ and ANDROID_HOME (platform 35, build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1).
set -euo pipefail
cd "$(dirname "$0")"
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest assembleDebug --console=plain "$@"
mkdir -p ../dist && cp app/build/outputs/apk/debug/app-debug.apk ../dist/mu-debug.apk
ls -l ../dist/mu-debug.apk
