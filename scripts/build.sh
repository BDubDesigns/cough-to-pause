#!/system/bin/sh
# On-device build for the Cough-to-Pause harness.
#
# Three overrides are required on Android, all discovered the hard way:
#   1. -Dorg.gradle.native=false        Gradle's native-platform .so is glibc, Android is bionic.
#   2. -Djava.io.tmpdir=$PREFIX/tmp     the Termux JDK hardcodes /data/data/com.termux/files/usr/tmp.
#   3. env -u LD_PRELOAD                libtermux-exec-ld-preload.so breaks the aapt2 daemon.
# plus aapt2 must be the aarch64 binary from the package repository, not Google's x86 build.
set -eu
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PREFIX/tmp/guh}"
export TMPDIR="$PREFIX/tmp"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
unset LD_PRELOAD
exec env -u LD_PRELOAD gradle "$@" --no-daemon \
  -Dorg.gradle.native=false \
  -Djava.io.tmpdir="$PREFIX/tmp" \
  -Dandroid.aapt2FromMavenOverride="$(command -v aapt2)"
