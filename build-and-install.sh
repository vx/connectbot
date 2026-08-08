#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

# keytool -genkeypair -keystore build/vx-connectbot-release.jks -alias connectbot -keyalg RSA -keysize 2048  -validity 10000

if [ "$1" == "debug" ]; then
  ./gradlew assembleDebug
  adb install -r 'build/outputs/apk/debug/VX ConnectBot-debug.apk'
  exit 0
fi

./gradlew assembleRelease

$ANDROID_HOME/build-tools/34.0.0/apksigner sign \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled false \
  --v4-signing-enabled false \
  --ks build/vx-connectbot-release.jks \
  --ks-key-alias connectbot \
  --out build/outputs/apk/release/VX-ConnectBot-1.8.0.apk \
  "build/outputs/apk/release/VX ConnectBot-release-unsigned.apk"

adb install -r 'build/outputs/apk/release/VX-ConnectBot-1.8.0.apk'
