#!/bin/sh
set -e

GRADLE_VERSION=8.10.2
DIST_URL=https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip
DIST_ROOT="$HOME/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin/zonewars"
GRADLE_HOME="$DIST_ROOT/gradle-${GRADLE_VERSION}"
ZIP_FILE="$DIST_ROOT/gradle-${GRADLE_VERSION}-bin.zip"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  echo "Downloading Gradle $GRADLE_VERSION..."
  mkdir -p "$DIST_ROOT"
  rm -f "$ZIP_FILE"
  if command -v curl >/dev/null 2>&1; then
    curl -L --fail --retry 3 --output "$ZIP_FILE" "$DIST_URL"
  else
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri '$DIST_URL' -OutFile '$ZIP_FILE'"
  fi
  rm -rf "$GRADLE_HOME"
  unzip -q "$ZIP_FILE" -d "$DIST_ROOT"
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
