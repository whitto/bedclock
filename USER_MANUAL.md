# Bedside Clock user manual

## Set up your Echo

Install and open Bedside Clock using the [installation instructions](README.md#install). Tap the gear on the clock face to open Settings. Tap **Done** to return to the clock.

- **Start at boot.** In Android Settings, open **Apps & notifications → Default apps → Home app** and select **Bedside Clock**. Home now opens the clock. Select the original launcher here to restore it.
- **Keep the full clock visible overnight.** In the app, turn on **Device → Keep screen awake**. The app can still dim the display using its own brightness settings.
- **Use the dim screensaver.** In Android’s **Display → Screen saver** settings, select **Bedside Clock** and choose when it should start. Turn off **Keep screen awake** to let the idle timer run on the clock face. The screensaver displays time and optional date. Wake the screen to use weather or settings.
- **Lock screen.** The clock can appear over the Android lock screen. Android continues to manage unlocking.
- **Leave the clock.** Enable **Device → Double-tap clock to exit**, then double-tap the clock face. On the supported LineageOS launcher, this opens the regular home screen when Bedside Clock is the default.

## Adjust the display

Under **Clock**, choose Digital or Flip clock, font, size and position. Time format can follow Android or use 12-hour or 24-hour time. Choose a gradient, black or custom solid background. Weekday, date and outside temperature have separate switches.

Under **Brightness**, choose **Schedule** for separate day and night levels, **Room light** to follow the ambient-light sensor, or **Manual** for a fixed level. In Room light mode, set **Dark-room minimum brightness** from 0% to 100%. At 0%, the clock face goes black at 1 lux or below and reappears at 3 lux or above. Tap anywhere on the black face to open Brightness settings. The wake brightness schedule can also brighten the clock. Room light follows the schedule if Android does not expose a sensor. **Red clock at night** follows the day/night times. **Gradually brighten** raises brightness from the night level to the day level over the selected interval while the clock is open. Set an alarm separately if you also want sound.

## Set an alarm

Tap **Settings → Alarm → Set time**. The bell on the face indicates an active alarm. The alarm rings once. To cancel it beforehand, select **Turn off**. When it rings, tap **Stop alarm** or press a volume button.

## Set up weather

1. Enable **Clock → Outside temperature**.
2. Under **Weather**, enable **Use PanTech outdoor sensor**. Enter the console’s local IPv4 address and tap **Save station IP**. The default address is `10.0.1.173`. The Echo must be able to reach the console over Wi-Fi. The live outdoor reading has been tested with a PanTech WS3900-series station.
3. Enter a four-digit Australian postcode, tap **Find locality**, and select your locality. This sets the forecast location. To use Bureau of Meteorology observations for temperature, turn off the PanTech switch and leave a locality selected.
4. Allow two minutes after the app detects Wi-Fi for the first sync. A reconnection restarts the wait. While the clock is open, it normally checks the station every minute, BOM observations every 15 minutes and forecasts every two hours.

Tap the temperature or the surrounding upper-left corner to see the rest of today followed by three full days. Today’s rainfall estimate counts only the remaining hours, with the current partial hour prorated. Rain chance is the highest hourly probability for each period. Each column gives the high and low in °C, expected conditions, rain chance and rainfall in millimetres. The panel closes after 30 seconds. Tap anywhere on the forecast or press Android Back to close it sooner. The station supplies current observations. Open-Meteo supplies the forecast.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| The simple dim clock replaces my flip clock | Enable **Device → Keep screen awake**. Adjust night brightness under **Brightness**. |
| The clock shows —°C | Allow the Wi-Fi startup delay. Check the station IP, the PanTech switch and whether the station’s Live Data page is reachable on the same network. For BOM, check that a locality is selected. |
| Temperature works but the forecast is missing | Check the saved locality and internet access. Local PanTech readings can work without internet. |
| Clock shows the wrong time | Check the time and time zone in Android Settings and the clock’s 12/24-hour preference. |
