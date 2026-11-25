#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_VERSION="9.5.0"
GRADLE_DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
CACHE_ROOT="${XDG_CACHE_HOME:-${HOME}/.cache}/homeradar"
GRADLE_HOME="${CACHE_ROOT}/gradle-${GRADLE_VERSION}"
GRADLE_BIN=""

if ! command -v java >/dev/null 2>&1; then
  echo "Error: JDK 17 is required. Install JDK 17 and try again." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi
if [[ -z "${ANDROID_SDK_ROOT:-}" && -n "${ANDROID_HOME:-}" ]]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "Error: Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
  exit 1
fi
if [[ ! -f "${ANDROID_HOME}/platforms/android-37/android.jar" ]]; then
  echo "Error: Android SDK Platform 37 is missing." >&2
  echo "Install it with: sdkmanager 'platforms;android-37'" >&2
  exit 1
fi

if command -v gradle >/dev/null 2>&1 && gradle --version 2>/dev/null | grep -Fqx "Gradle ${GRADLE_VERSION}"; then
  GRADLE_BIN="$(command -v gradle)"
else
  GRADLE_BIN="${GRADLE_HOME}/bin/gradle"
fi

if [[ ! -x "$GRADLE_BIN" ]]; then
  mkdir -p "$CACHE_ROOT"
  TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/homeradar-gradle.XXXXXX")"
  trap 'rm -rf "$TEMP_DIR"' EXIT

  echo "Downloading Gradle ${GRADLE_VERSION} (binary distribution)…"
  curl --fail --location --silent --show-error --retry 3 \
    "$GRADLE_DIST_URL" -o "${TEMP_DIR}/gradle.zip"
  curl --fail --location --silent --show-error --retry 3 \
    "${GRADLE_DIST_URL}.sha256" -o "${TEMP_DIR}/gradle.zip.sha256"

  EXPECTED_SHA="$(awk '{print $1}' "${TEMP_DIR}/gradle.zip.sha256")"
  ACTUAL_SHA="$(sha256sum "${TEMP_DIR}/gradle.zip" | awk '{print $1}')"
  if [[ -z "$EXPECTED_SHA" || "$ACTUAL_SHA" != "$EXPECTED_SHA" ]]; then
    echo "Error: Gradle distribution SHA-256 verification failed." >&2
    exit 1
  fi

  command -v unzip >/dev/null 2>&1 || {
    echo "Error: unzip is required to unpack Gradle." >&2
    exit 1
  }
  mkdir -p "$GRADLE_HOME"
  unzip -q "${TEMP_DIR}/gradle.zip" -d "$TEMP_DIR"
  rm -rf "$GRADLE_HOME"
  mv "${TEMP_DIR}/gradle-${GRADLE_VERSION}" "$GRADLE_HOME"
  GRADLE_BIN="${GRADLE_HOME}/bin/gradle"
fi

echo "Building HomeRadar debug APK with Gradle ${GRADLE_VERSION}…"
cd "$ROOT_DIR"
"$GRADLE_BIN" --no-daemon --max-workers=2 --build-cache --console=plain \
  :android:app:assembleDebug "$@"

APK_PATH="${ROOT_DIR}/android/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -s "$APK_PATH" ]]; then
  echo "Error: Gradle completed but the debug APK was not found: $APK_PATH" >&2
  exit 1
fi

echo
echo "Build successful:"
echo "$APK_PATH"
