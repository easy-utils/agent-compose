#!/usr/bin/env bash
# Build the COMPOSE DESKTOP macOS DMG. Desktop only.
#
# This is the script the macOS build workers run. It deliberately does NOT build
# the wasm/web target: `:wasmJsBrowserDistribution` pulls the whole Kotlin/JS
# toolchain into GRADLE_USER_HOME (Node ~223 MB, Yarn, binaryen, and the npm
# packages under ~/.kotlin/kotlin-npm-tooling ~132 MB) for webpack, none of which
# a macOS worker needs. The web build belongs to the web build host.
#
# Required on the machine: JDK 17 including `jpackage` (Temurin), Xcode (jpackage
# shells out to hdiutil/codesign), and network access for the Gradle distribution
# plus Maven dependencies. An Android SDK is NOT required: `:android` is wired in
# conditionally (see settings.gradle.kts) and is skipped without one.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Gradle 9.7.1 is mandatory, not cosmetic: on 9.6/9.7.0 the Compose plugin's
# :proguardReleaseJars dies with "getStandardOutput(...) must not be null".
TASKS=(clean :proguardReleaseJars packageReleaseDmg)

# Guard: this script is desktop-only. Fail fast if someone adds a web/js task,
# so the Node/Yarn/npm toolchain never gets downloaded here again.
for t in "${TASKS[@]}"; do
  case "$t" in
    *wasm*|*Wasm*|*jsBrowser*|*JsBrowser*|*BrowserDistribution*)
      echo "error: '$t' is a web task and must not run on the macOS worker" >&2
      exit 2
      ;;
  esac
done

GRADLE="${GRADLE:-./gradlew}"
echo "==> $GRADLE ${TASKS[*]}"
"$GRADLE" --no-daemon "${TASKS[@]}"

DMG=$(find build/compose/binaries -name '*.dmg' | head -1)
if [ -z "$DMG" ]; then
  echo "error: no DMG produced" >&2
  exit 1
fi
echo "==> $DMG"
ls -l "$DMG"
shasum -a 256 "$DMG"
