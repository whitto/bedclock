# Bedside Clock

Bedside Clock turns an Echo Show 5 running LineageOS into a landscape bedside display. It includes digital and flip faces, adjustable night brightness, a one-shot alarm, local weather station readings and a forecast for the rest of today and the next three days. It can also serve as Android's Home app or screensaver.

**Version 5.0.8** adds five digit colour presets with independent day and night selections. Bedside lamps uses grey controls on black, follows clock brightness, and refreshes lamp power and slider readings while open.

See the [user manual](USER_MANUAL.md) for setup and daily use, and the [release notes](worktodate.md) for changes and verification status.

## Build

The app targets Android 11 and requires Android 8.0 or newer. It has been tested on a second-generation Echo Show 5 running LineageOS. The build script uses the Android SDK directly.

1. Install JDK 17 or newer, Android SDK Platform 35 and Build Tools 35.0.0.
2. Set `JAVA_HOME` to the JDK directory and `ANDROID_SDK_ROOT` to the Android SDK directory. On macOS, the JDK directory typically ends in `Contents/Home`.
3. From the repository root run `./build.sh`. The script compiles the app and verifies the signature and alignment of `build/BedsideClock.apk`.

The first build generates a local development signing key in `build/clock-debug.p12`. Keep the original key for future updates. To build with an existing key stored elsewhere, set `CLOCK_KEYSTORE` to its absolute path.

## Install

Download the signed [BedsideClock.apk](BedsideClock.apk) for the Echo Show 5, or use your local build.

With Android platform-tools installed, connect the Echo over USB, enable USB debugging and accept the authorisation prompt on the device. Run:

```sh
adb devices
adb install -r BedsideClock.apk
```

For a local build, use `build/BedsideClock.apk` instead. Open **Bedside Clock** from the app drawer. Updating preserves your settings when the signing key matches.

## Data and fonts

Weather uses the configured PanTech station, Bureau of Meteorology observations and Open-Meteo forecasts. Australian postcode data is from GeoNames. The repository includes the [postcode attribution](assets/POSTCODES-LICENSE.txt) and the font licences for [Inter](assets/fonts/OFL.txt) and [Oswald](assets/fonts/Oswald-OFL.txt).
