#!/usr/bin/env bash
# Downloads a JDK and the Android SDK into .toolchain/ (no sudo, no global install).
set -euo pipefail
source "$(dirname "$0")/env.sh"
mkdir -p "$TC"
cd "$TC"

if [ ! -x jdk/bin/javac ]; then
  echo "Downloading JDK 21 (Temurin)..."
  curl -fsSL -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk && tar -xzf jdk.tgz -C jdk --strip-components=1 && rm jdk.tgz
fi

if [ ! -x android-sdk/cmdline-tools/latest/bin/sdkmanager ]; then
  echo "Downloading Android command-line tools..."
  curl -fsSL -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
  rm -rf clt && unzip -q clt.zip -d clt && rm clt.zip
  mkdir -p android-sdk/cmdline-tools && mv clt/cmdline-tools android-sdk/cmdline-tools/latest && rm -rf clt
fi

yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
echo "Toolchain ready in $TC"
