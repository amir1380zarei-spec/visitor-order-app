#!/bin/sh
set -eu
GRADLE_VERSION=8.7
BASE="$HOME/.cache/visitor-gradle"
DIST="$BASE/gradle-$GRADLE_VERSION"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE"
  ARCHIVE="$BASE/gradle.zip"
  if [ ! -f "$ARCHIVE" ]; then curl -L --fail -o "$ARCHIVE" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"; fi
  rm -rf "$DIST.tmp"
  mkdir -p "$DIST.tmp"
  unzip -q "$ARCHIVE" -d "$DIST.tmp"
  mv "$DIST.tmp/gradle-$GRADLE_VERSION" "$DIST"
  rm -rf "$DIST.tmp"
fi
exec "$DIST/bin/gradle" "$@"
