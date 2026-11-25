# Bedside Clock work to date

## 5.0.11

- Weather sync begins when Android confirms internet access over Wi-Fi, or after three seconds on a stable Wi-Fi connection. While waiting for the first PanTech reading, the clock retries after five seconds and caps the retry wait at ten seconds. After a successful reading, normal one-minute station checks resume.
- On the test Echo the first station request succeeded in 0.54 seconds. The retry cap was not exercised on that run.
- Version 5.0.9 added Spotify startup after reboot. The Echo appeared in the phone’s Spotify Connect device list after the automatic return to the clock. Version 5.0.10 removed the two-minute Wi-Fi startup wait.

## 5.0.8

- Choose from White, Amber, Red, Green and Blue for day and night digits, with a live preview.
- Bedside lamps uses grey controls on black and follows the clock’s brightness, including its overnight dimming and wake ramp.
- Lamp controls refresh power and brightness while open and check the reported state after a change.
- Installed and checked the picker and bedside lamps screen on a second-generation Echo Show 5.

## Current release

Version **5.0.4** (Android version code **36**) uses application ID `au.clock.display`.

- **Clock** includes a live face preview and collapses the weekday, date and outside-temperature switches under **Show on clock**.
- **Brightness** puts mode selection at the top and shows controls for the chosen mode. The red-night setting, wake ramp and tap-to-recover behaviour at 0% are still available.
- **Alarm** shows the one-shot alarm state and opens the time picker when enabling it.
- **Weather** groups PanTech station configuration separately from postcode and forecast location. **Device** keeps the screen and gesture switches together.
- The bottom-left gear from 5.0.3 opens the new grey sidebar.

The 5.0.4 APK was built, checked for alignment and verified against the 5.0.3 signing certificate. The 5.0.4 screens have not yet been tested on the Echo.

## Existing features

- The clock supports digital and flip faces, scheduled and sensor-based dimming, and a one-shot alarm.
- Weather supports an editable local PanTech station address, BOM observations and Open-Meteo forecasts. Startup sync begins when Wi-Fi is ready. Tapping the temperature opens the rest of today and the next three full days. Tap again or wait 30 seconds to close.
- Spotify briefly opens once per reboot when enabled, then the clock returns. The Spotify button opens the player. On the test Echo, a pause returned to the clock after about five seconds with Android's **Display over other apps** permission enabled.
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
