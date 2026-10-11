#!/usr/bin/env bash
# Builds the debug APK into app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$ROOT"
./gradlew --no-daemon assembleDebug "$@"
