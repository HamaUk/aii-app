#!/usr/bin/env bash
# Builds Nexus and copies the APKs into artifacts/.
#
# Why the explicit heap flags: AGP 9 + KSP + R8 want more memory than a small CI runner has. These
# numbers are tuned for a 2-core / 2 GB machine (with swap) - raise them on a real dev box and drop
# the flags entirely if you have 8 GB+.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ANDROID_SDK_DIR="${ANDROID_SDK_DIR:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

# Gradle compiles a generated version catalog and therefore needs javac. A JAVA_HOME pointing at a
# JRE fails deep inside Gradle with "No Java compiler found, please ensure you are running Gradle
# with a JDK" - fail here instead, where the cause is obvious. Any JDK 17+ works.
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
  echo "ERROR: JAVA_HOME is not a JDK (no executable javac): $JAVA_HOME" >&2
  echo "       Set JAVA_HOME to a full JDK 17 or newer, e.g.:" >&2
  echo "         export JAVA_HOME=\$(dirname \$(dirname \$(readlink -f \$(which javac))))" >&2
  exit 1
fi

AFFECTED="${1:-both}"   # debug | release | both

GRADLE_FLAGS=(
  --console=plain
  -Dorg.gradle.jvmargs="-Xmx1280m -XX:MaxMetaspaceSize=512m"
  -Dkotlin.daemon.jvmargs="-Xmx1024m"
)

echo "==> SDK:   $ANDROID_HOME"
echo "==> JDK:   $JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1))"

./gradlew "${GRADLE_FLAGS[@]}" :core:ai:test :app:testDebugUnitTest

mkdir -p artifacts
if [[ "$AFFECTED" == "debug" || "$AFFECTED" == "both" ]]; then
  ./gradlew "${GRADLE_FLAGS[@]}" :app:assembleDebug
  cp app/build/outputs/apk/debug/app-debug.apk artifacts/nexus-1.0.0-debug.apk
fi
if [[ "$AFFECTED" == "release" || "$AFFECTED" == "both" ]]; then
  ./gradlew "${GRADLE_FLAGS[@]}" :app:assembleRelease
  cp app/build/outputs/apk/release/app-release.apk artifacts/nexus-1.0.0-release.apk
fi

echo
echo "==> artifacts/"
ls -lh artifacts/*.apk
