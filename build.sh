#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK directory}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK installation}"
export JAVA_HOME
tools="$ANDROID_SDK_ROOT/build-tools/35.0.0"
platform="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex" build

"$tools/aapt2" compile --dir res -o "$work/resources.zip"
"$tools/aapt2" link -I "$platform" --manifest AndroidManifest.xml -A assets --java "$work/generated" \
    -o "$work/unsigned.apk" "$work/resources.zip"
"$JAVA_HOME/bin/javac" --release 8 -classpath "$platform" -d "$work/classes" \
    src/au/clock/display/*.java "$work/generated/au/clock/display/R.java"
"$JAVA_HOME/bin/jar" cf "$work/classes.jar" -C "$work/classes" .
"$tools/d8" --release --min-api 26 --lib "$platform" --output "$work/dex" "$work/classes.jar"
(cd "$work/dex" && zip -q "$work/unsigned.apk" classes.dex)
"$tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"

keystore="${CLOCK_KEYSTORE:-build/clock-debug.p12}"
if [[ ! -f "$keystore" ]]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$keystore" -storetype PKCS12 \
        -storepass android -keypass android -alias clock -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Bedside Clock Debug"
fi

"$tools/apksigner" sign --ks "$keystore" --ks-key-alias clock --ks-pass pass:android \
    --out "$work/BedsideClock.apk" "$work/aligned.apk"
"$tools/apksigner" verify --verbose "$work/BedsideClock.apk"
"$tools/zipalign" -c 4 "$work/BedsideClock.apk"
cp "$work/BedsideClock.apk" build/BedsideClock.apk
