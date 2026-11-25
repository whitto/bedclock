# Bedside Clock work to date

## Current release

Version **5.0** (Android version code **32**) is installed on a second-generation Echo Show 5 running LineageOS. The application ID is `au.clock.display`. The app is the selected Home app and screensaver on the test Echo.

- The clock supports digital and flip faces, scheduled and sensor-based dimming, and a one-shot alarm.
- Weather supports an editable local PanTech station address, BOM observations and Open-Meteo forecasts. Startup sync waits two minutes after Wi-Fi becomes available. Tapping the temperature opens the rest of today and the next three full days. Tap again or wait 30 seconds to close.
- Spotify loads in the background when the clock starts. The Spotify button opens the player. On the test Echo, a pause returned to the clock after about five seconds with Android's **Display over other apps** permission enabled.
- The amber house opens a full-screen Home Assistant panel with separate power and brightness controls for **Lamp Craig** and **Lamp Kristen**. The 5.0 build loaded both live lamp states at 25%. That check did not exercise power or dimming writes.

## Where to pick up

| Area | Source |
| --- | --- |
| Clock, settings and launcher entry | `src/au/clock/display/ClockActivity.java` |
| Lamp panel and Home Assistant REST calls | `src/au/clock/display/BedsideLights.java` |
| Screensaver | `src/au/clock/display/ClockDream.java` |
| Spotify launch and playback monitoring | `src/au/clock/display/SpotifyLink.java` |
| Weather and forecast parsing | `PanTechWeather.java`, `BomWeather.java`, `ForecastWeather.java` |
| Build and installation | [README](README.md) |
| Device setup and controls | [User manual](USER_MANUAL.md) |

Use JDK 17 or later, Android Platform 35 and Build Tools 35.0.0. Set `JAVA_HOME` and `ANDROID_SDK_ROOT`, then run `./build.sh`. Install with `adb install -r build/BedsideClock.apk`. Updates to the Echo must use the original signing key kept by the device owner. Home Assistant connection settings are entered on the Echo through the lamp panel's connection gear.

On the test Echo, both the selected and fallback screensaver settings point to `au.clock.display/au.clock.display.ClockDream`. Android's ambient/always-on display was disabled after it took over from Bedside Clock. A manual screensaver launch opened the full clock. Persistence of that fallback fix across reboot remains unverified.

The **11:00pm Daily** action is a future feature. The requested targets are **Lounge TV Room** and **Downstairs**. Confirm their exact Home Assistant entity IDs before adding a manual action.
