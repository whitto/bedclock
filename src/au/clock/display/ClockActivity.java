package au.clock.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.style.ReplacementSpan;
import android.text.format.DateFormat;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClockActivity extends Activity implements SensorEventListener {
    private static final int SETTINGS_BACKGROUND = 0xff1d1e20;
    private static final int SETTINGS_BORDER = 0xff414345;
    private static final int SETTINGS_TEXT = 0xfff4f4f5;
    private static final int SETTINGS_SECONDARY = 0xffc3c5c7;
    private static final String[] SETTINGS_TABS = {"Clock", "Brightness", "Alarm", "Weather", "Device"};
    private static final long WEATHER_INTERVAL = 15 * 60_000L;
    private static final long WEATHER_STALE = 45 * 60_000L;
    private static final long FORECAST_INTERVAL = 2 * 60 * 60_000L;
    private static final long FORECAST_STALE = 3 * 60 * 60_000L;
    private static final String[] FONTS = {"sans-serif-light", "sans-serif-medium", "serif", "monospace"};
    private static final String[] FONT_LABELS = {
            "Light sans", "Medium sans", "Serif", "Monospace", "Inter Bold", "Oswald Bold (flip clock)",
            "Roboto Condensed Bold"
    };
    private static final int[] WAKE_DURATIONS = {5, 10, 15, 30, 45, 60};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private static final long WIFI_SETTLE_MS = 120_000L;
    private Network weatherWifi;
    private long weatherReadyAt;
    private final Runnable syncAfterWifi = this::updateWeather;
    private final ConnectivityManager.NetworkCallback wifiChanges = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            if (isDestroyed()) return;
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                clearWeatherWifi();
            } else if (!network.equals(weatherWifi)) {
                clearWeatherWifi();
                weatherWifi = network;
                weatherReadyAt = SystemClock.elapsedRealtime() + WIFI_SETTLE_MS;
                nextWeather = nextForecast = 0;
                weatherRetry = forecastRetry = 5000L;
                android.util.Log.i("BedsideClock", "Wi-Fi available; waiting two minutes for weather");
                handler.postDelayed(syncAfterWifi, WIFI_SETTLE_MS);
            }
        }

        @Override public void onLost(Network network) {
            if (network.equals(weatherWifi)) clearWeatherWifi();
        }
    };

    private void clearWeatherWifi() {
        handler.removeCallbacks(syncAfterWifi);
        weatherWifi = null;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            renderClock();
            updateBrightness();
            updateWeather();
            updateAlarm();
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    private SharedPreferences preferences;
    private FrameLayout root;
    private LinearLayout face;
    private TextView time;
    private FlipClockView flipClock;
    private Typeface displayTypeface;
    private TextView period;
    private TextView date;
    private TextView temperature;
    private SpotifyLink spotify;
    private BedsideLights bedsideLights;
    private ImageButton settingsButton;
    private ImageButton alarmBell;
    private AlertDialog ringingDialog;
    private boolean stoppingWithVolumeKey;
    private LinearLayout settings;
    private ScrollView settingsContent;
    private LinearLayout settingsBody;
    private TextView settingsTitle;
    private Runnable settingsPreview;
    private final Button[] settingsTabs = new Button[SETTINGS_TABS.length];
    private int settingsTab;
    private SensorManager sensors;
    private Sensor lightSensor;
    private float lightFraction = -1;
    private View darkCover;
    private boolean roomDark;
    private int appliedBrightness = -1;
    private int clockForeground;
    private int clockSecondary;
    private long nextWeather;
    private long weatherRetry = 5000L;
    private long forecastRetry = 5000L;
    private long weatherTime;
    private BomWeather.Reading lastReading;
    private boolean weatherFailed;
    private boolean weatherInFlight;
    private LinearLayout weatherPanel;
    private final Runnable dismissForecast = this::closeWeatherPanel;
    private ForecastWeather.Outlook days;
    private String daysLocation = "";
    private long nextDays;
    private boolean daysLoading;
    private ForecastWeather forecast;
    private ForecastWeather displayedForecast;
    private int displayedForecastColor;
    private long forecastTime;
    private long nextForecast;
    private boolean forecastInFlight;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getPreferences(MODE_PRIVATE);
        if (preferences.getBoolean("dim", false) && !preferences.contains("brightnessMode")) {
            preferences.edit().putInt("brightnessMode", 2).putInt("manualLevel", 8).apply();
        }
        sensors = getSystemService(SensorManager.class);
        lightSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(wifiChanges, handler);
        bedsideLights = new BedsideLights(this);
        spotify = new SpotifyLink(this);
        spotify.connect();
        buildClock();
        showClock();
        DailyAlarm.schedule(this);
        handleAlarmIntent(getIntent());
    }

    private void buildClock() {
        root = new FrameLayout(this);
        face = new LinearLayout(this);
        face.setOrientation(LinearLayout.VERTICAL);
        face.setGravity(Gravity.CENTER);
        LinearLayout digits = new LinearLayout(this);
        digits.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        time = text(100);
        time.setIncludeFontPadding(false);
        time.setSingleLine();
        digits.addView(time);
        flipClock = new FlipClockView(this);
        flipClock.setVisibility(View.GONE);
        digits.addView(flipClock);
        period = text(18);
        period.setIncludeFontPadding(false);
        period.setPadding(dp(8), 0, 0, 0);
        digits.addView(period);
        face.addView(digits);
        date = text(20);
        date.setPadding(0, dp(6), 0, 0);
        face.addView(date);
        root.addView(face);

        temperature = text(16);
        temperature.setMaxLines(1);
        temperature.setPaddingRelative(0, 0, dp(2), 0);
        temperature.setCompoundDrawablePadding(dp(6));
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        top.setMargins(dp(14), dp(8), dp(14), 0);
        FrameLayout weatherTarget = new FrameLayout(this);
        weatherTarget.addView(temperature, top);
        root.addView(weatherTarget, new FrameLayout.LayoutParams(dp(136), dp(56), Gravity.TOP | Gravity.START));
        weatherTarget.setOnClickListener(view -> openWeatherPanel());
        temperature.setOnClickListener(view -> openWeatherPanel());

        alarmBell = new ImageButton(this);
        alarmBell.setImageResource(R.drawable.alarm_bell);
        alarmBell.setBackgroundColor(Color.TRANSPARENT);
        alarmBell.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        FrameLayout.LayoutParams bellPosition = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.TOP | Gravity.RIGHT);
        root.addView(alarmBell, bellPosition);
        alarmBell.setOnClickListener(view -> openAlarm());

        settingsButton = new ImageButton(this);
        settingsButton.setImageResource(R.drawable.settings_gear);
        settingsButton.setContentDescription("Settings");
        settingsButton.setBackgroundColor(Color.TRANSPARENT);
        settingsButton.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM | Gravity.LEFT);
        bottom.setMargins(dp(12), 0, 0, dp(10));
        root.addView(settingsButton, bottom);
        settingsButton.setOnClickListener(view -> openSettings());

        try {
            ImageButton player = new ImageButton(this);
            player.setImageDrawable(getPackageManager().getApplicationIcon(SpotifyLink.PACKAGE));
            player.setContentDescription("Open Spotify");
            player.setBackgroundColor(Color.TRANSPARENT);
            player.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            player.setPadding(dp(7), dp(7), dp(7), dp(7));
            FrameLayout.LayoutParams slot = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM | Gravity.RIGHT);
            slot.setMargins(0, 0, dp(60), dp(10));
            root.addView(player, slot);
            player.setOnClickListener(view -> spotify.open());
        } catch (android.content.pm.PackageManager.NameNotFoundException absent) {
            // Devices without Spotify display just the settings gear.
        }

        ImageButton homeAssistant = new ImageButton(this);
        homeAssistant.setImageResource(R.drawable.bedside_home);
        homeAssistant.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        homeAssistant.setContentDescription("Home Assistant lamps");
        homeAssistant.setBackgroundColor(Color.TRANSPARENT);
        homeAssistant.setPadding(0, 0, 0, 0);
        FrameLayout.LayoutParams lampSlot = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM | Gravity.RIGHT);
        lampSlot.setMargins(0, 0, dp(108), dp(10));
        root.addView(homeAssistant, lampSlot);
        homeAssistant.setOnClickListener(view -> bedsideLights.open());

        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (settings != null) return false;
                if (preferences.getBoolean("doubleTap", true)) {
                    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                    android.content.pm.ResolveInfo resolved = getPackageManager().resolveActivity(home,
                            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
                    if (resolved != null && getPackageName().equals(resolved.activityInfo.packageName)) {
                        home.setPackage("com.android.launcher3");
                        try {
                            startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (android.content.ActivityNotFoundException unavailable) {
                            startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
                        }
                    }
                    finish();
                    return true;
                }
                return false;
            }
        });
        root.setOnTouchListener((view, event) -> gestures.onTouchEvent(event));
        root.addOnLayoutChangeListener((view, left, topEdge, right, bottomEdge, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottomEdge - topEdge != oldBottom - oldTop) resizeClock();
        });
        setContentView(root);
    }

    private void showClock() {
        if (settings != null) {
            getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(settings.getWindowToken(), 0);
            root.removeView(settings);
            settings = null;
            settingsContent = null;
            settingsBody = null;
            settingsTitle = null;
            settingsPreview = null;
            java.util.Arrays.fill(settingsTabs, null);
        }
        int background = preferences.getInt("background", 0);
        int solid = preferences.getInt("solid", 0xff18232d);
        boolean bright = background == 2 && android.graphics.Color.luminance(solid) > .179;
        int foreground = bright ? 0xff151421 : 0xfff6f3ff;
        int secondary = bright ? 0xff302c42 : 0xffe1d8f2;
        root.setBackground(clockBackground(background, solid));
        clockForeground = foreground;
        clockSecondary = secondary;
        date.setTextColor(secondary);
        temperature.setTextColor(foreground);
        alarmBell.setColorFilter(foreground);
        settingsButton.setColorFilter(foreground);
        displayTypeface = clockTypeface(preferences.getInt("font", 0));
        time.setTypeface(displayTypeface);
        boolean flipping = preferences.getInt("displayStyle", 0) == 1;
        time.setVisibility(flipping ? View.GONE : View.VISIBLE);
        flipClock.setVisibility(flipping ? View.VISIBLE : View.GONE);
        date.setVisibility(preferences.getBoolean("showWeekday", true)
                || preferences.getBoolean("showDate", true) ? View.VISIBLE : View.GONE);
        temperature.setVisibility(preferences.getBoolean("showTemperature", true) ? View.VISIBLE : View.GONE);
        ((View) temperature.getParent()).setVisibility(temperature.getVisibility());

        int position = preferences.getInt("position", 0);
        int gravity = position == 0 ? Gravity.CENTER : Gravity.BOTTOM |
                (position == 1 ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(-2, -2, gravity);
        layout.setMargins(dp(10), 0, dp(10), position == 0 ? 0 : dp(58));
        face.setGravity(position == 2 ? Gravity.START : Gravity.CENTER);
        face.setLayoutParams(layout);
        if (preferences.getBoolean("keepAwake", true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        renderClock();
        resizeClock();
        showWeather();
        updateBrightness();
        updateAlarm();
        immersive();
    }

    private void resizeClock() {
        int width = root.getWidth();
        int height = root.getHeight();
        if (width == 0 || height == 0) return;
        float suffixWidth = period.getVisibility() == View.GONE ? 0
                : period.getPaint().measureText(period.getText().toString()) + period.getPaddingLeft();
        float fullWidth = Math.max(1, width - 2 * dp(10) - suffixWidth);
        int position = preferences.getInt("position", 0);
        int weatherSpace = temperature.getVisibility() == View.GONE ? dp(10)
                : dp(8) + temperature.getLineHeight() + dp(4);
        int dateSpace = date.getVisibility() == View.GONE ? 0 : date.getLineHeight() + date.getPaddingTop();
        float fullHeight = position == 0
                ? Math.min(height - 2f * weatherSpace, height - 2f * (dateSpace + dp(10)))
                : height - weatherSpace - dateSpace - dp(58);
        fullHeight = Math.max(1, fullHeight);

        float scale = preferences.getInt("size", 100) / 100f;
        float fill = scale <= 1f ? scale * .94f : .94f + (scale - 1f) * .30f;
        String reading = time.getText().toString();
        if (flipClock.getVisibility() == View.VISIBLE) {
            float tileWidth = Math.max(1f, FlipClockView.widthAtUnitSize(reading));
            float fit = Math.min(fullWidth / tileWidth, fullHeight / FlipClockView.heightAtUnitSize());
            flipClock.setDigitSize(fit * fill);
            return;
        }
        // Measure the actual time so shorter readings can fill the face too.
        android.graphics.Paint measure = new android.graphics.Paint(time.getPaint());
        measure.setTextSize(100f);
        android.graphics.Paint.FontMetricsInt metrics = measure.getFontMetricsInt();
        float glyphWidth = Math.max(1, measure.measureText(reading));
        float lineHeight = metrics.descent - metrics.ascent;
        float fit = Math.min(fullWidth / glyphWidth, fullHeight / lineHeight);
        float size = 100f * fit * fill;
        time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
    }

    private Typeface clockTypeface(int index) {
        switch (index) {
            case 4:
                return new Typeface.Builder(getAssets(), "fonts/Inter.ttf")
                        .setFontVariationSettings("'wght' 700, 'opsz' 32").build();
            case 5:
                return new Typeface.Builder(getAssets(), "fonts/Oswald.ttf")
                        .setFontVariationSettings("'wght' 700").build();
            case 6:
                return Typeface.create("sans-serif-condensed", Typeface.BOLD);
            default:
                return Typeface.create(FONTS[index], Typeface.NORMAL);
        }
    }

    private void renderClock() {
        Date now = new Date();
        boolean redAtNight = preferences.getBoolean("redAtNight", false) && !isDaytime();
        int digitsColor = redAtNight ? 0xffff4545 : clockForeground;
        int periodColor = redAtNight ? 0xffff4545 : clockSecondary;
        if (time.getCurrentTextColor() != digitsColor) time.setTextColor(digitsColor);
        if (period.getCurrentTextColor() != periodColor) period.setTextColor(periodColor);
        boolean use24Hours = is24HourClock();
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        CharSequence reading = DateFormat.format(use24Hours ? "HH:mm" : "h:mm", now);
        if (flipClock.getVisibility() == View.VISIBLE) {
            flipClock.setFace(displayTypeface, digitsColor, Color.luminance(clockForeground) < .5f);
            flipClock.setReading(reading.toString());
        }
        boolean timeChanged = !reading.toString().contentEquals(time.getText());
        if (timeChanged) {
            SpannableString display = new SpannableString(reading);
            int colon = reading.toString().indexOf(':');
            if (colon >= 0) display.setSpan(new CenteredColon(), colon, colon + 1, 0);
            time.setText(display);
        }
        boolean periodChanged = setText(period, use24Hours ? "" : DateFormat.format("aa", now));
        int visibility = use24Hours ? View.GONE : View.VISIBLE;
        boolean visibilityChanged = period.getVisibility() != visibility;
        if (visibilityChanged) period.setVisibility(visibility);
        if (date.getVisibility() == View.VISIBLE) {
            boolean weekday = preferences.getBoolean("showWeekday", true);
            boolean calendarDate = preferences.getBoolean("showDate", true);
            String skeleton = weekday ? (calendarDate ? "EEEE MMMM d" : "EEEE") : "MMMM d";
            setText(date, DateFormat.format(DateFormat.getBestDateTimePattern(locale, skeleton), now));
        }
        boolean centeredDate = date.getVisibility() == View.VISIBLE && preferences.getInt("position", 0) == 0;
        face.setTranslationY(centeredDate ? date.getHeight() / 2f : 0);
        if (timeChanged || periodChanged || visibilityChanged) resizeClock();
    }

    private boolean setText(TextView view, CharSequence value) {
        if (value.toString().contentEquals(view.getText())) return false;
        view.setText(value);
        return true;
    }

    private static final class CenteredColon extends ReplacementSpan {
        @Override public int getSize(Paint paint, CharSequence text, int start, int end,
                Paint.FontMetricsInt metrics) {
            return (int) Math.ceil(paint.measureText(text, start, end));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int baseline, int bottom, Paint paint) {
            Rect digit = new Rect();
            Rect colon = new Rect();
            paint.getTextBounds("0123456789", 0, 10, digit);
            paint.getTextBounds(":", 0, 1, colon);
            float offset = (digit.top + digit.bottom - colon.top - colon.bottom) / 2f;
            canvas.drawText(text, start, end, x, baseline + offset, paint);
        }
    }

    private int scheduleLevel() {
        return isDaytime() ? preferences.getInt("dayLevel", 80) : preferences.getInt("nightLevel", 10);
    }

    private boolean isDaytime() {
        LocalDateTime now = LocalDateTime.now();
        int minute = now.getHour() * 60 + now.getMinute();
        int day = preferences.getInt("dayStart", 360);
        int night = preferences.getInt("nightStart", 1320);
        return day <= night ? minute >= day && minute < night : minute >= day || minute < night;
    }

    private void updateBrightness() {
        int mode = preferences.getInt("brightnessMode", 0);
        int level = scheduleLevel();
        if (mode == 1 && lightFraction >= 0) {
            int min = preferences.getInt("darkRoomMinimum", preferences.getInt("nightLevel", 10));
            int max = preferences.getInt("dayLevel", 80);
            level = roomDark ? min : Math.max(1, Math.round(min + (Math.max(min, max) - min) * lightFraction));
        } else if (mode == 2) {
            level = preferences.getInt("manualLevel", 50);
        }
        if (preferences.getBoolean("wakeEnabled", false)) {
            int now = java.time.LocalTime.now().toSecondOfDay();
            int wake = preferences.getInt("wakeStart", 360) * 60;
            int night = preferences.getInt("nightStart", 1320) * 60;
            int sinceWake = Math.floorMod(now - wake, 86400);
            int untilNight = Math.floorMod(night - wake - 1, 86400) + 1;
            if (sinceWake < untilNight) {
                int duration = WAKE_DURATIONS[preferences.getInt("wakeDuration", 3)] * 60;
                int nightLevel = preferences.getInt("nightLevel", 10);
                int wakeLevel = Math.max(nightLevel, preferences.getInt("dayLevel", 80));
                float progress = Math.min(1f, sinceWake / (float) Math.min(duration, untilNight));
                level = Math.round(nightLevel + (wakeLevel - nightLevel) * progress);
            }
        }
        if (settings != null || AlarmSound.isRinging()) level = Math.max(level, 35);
        if (level == appliedBrightness) return;
        if (level < 5) {
            if (darkCover == null) {
                darkCover = new View(this);
                darkCover.setElevation(dp(12));
                darkCover.setOnClickListener(view -> {
                    closeWeatherPanel();
                    openSettings();
                    showSettingsPage(1);
                    updateBrightness();
                });
                root.addView(darkCover, new FrameLayout.LayoutParams(-1, -1));
            }
            // The panel can clamp low backlight requests. Dim the rendered pixels as well.
            int shade = 255 - level * 51;
            darkCover.setBackgroundColor(Color.argb(shade, 0, 0, 0));
            darkCover.setClickable(level == 0);
            darkCover.setFocusable(level == 0);
            darkCover.setContentDescription(level == 0 ? "Open brightness settings" : null);
            darkCover.setImportantForAccessibility(level == 0
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        } else if (darkCover != null) {
            root.removeView(darkCover);
            darkCover = null;
        }
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = level / 100f;
        getWindow().setAttributes(attributes);
        appliedBrightness = level;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        float lux = Math.max(0, event.values[0]);
        roomDark = lux <= 1f || (roomDark && lux < 3f);
        float fraction = (float) Math.min(1, Math.log1p(lux) / Math.log1p(250));
        lightFraction = lightFraction < 0 ? fraction : lightFraction * .75f + fraction * .25f;
        updateBrightness();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override protected void onResume() {
        super.onResume();
        spotify.stopWatching();
        if (lightSensor != null) sensors.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (lastReading == null) nextWeather = 0;
        if (forecast == null) nextForecast = 0;
        handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        if (sensors != null) sensors.unregisterListener(this);
        lightFraction = -1;
        super.onPause();
    }

    @Override protected void onDestroy() {
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifiChanges);
        clearWeatherWifi();
        handler.removeCallbacks(dismissForecast);
        spotify.close();
        bedsideLights.close();
        network.shutdownNow();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
    }

    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (settings == null) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(settings == null
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : 0);
        }
    }

    private String stationAddress() {
        return preferences.getString("panTechAddress", "10.0.1.173");
    }

    private void refreshTemperature() {
        lastReading = null;
        nextWeather = 0;
        showWeather();
        updateWeather();
    }

    private void updateWeather() {
        if (!preferences.getBoolean("showTemperature", true)) return;
        if (weatherWifi == null || SystemClock.elapsedRealtime() < weatherReadyAt) return;
        String location = preferences.getString("locationKey", "");
        String state = preferences.getString("weatherState", "");
        double latitude = Double.longBitsToDouble(preferences.getLong("latitude", 0));
        double longitude = Double.longBitsToDouble(preferences.getLong("longitude", 0));
        boolean local = preferences.getBoolean("usePanTech", true);
        updateDays(location, latitude, longitude);
        if (!local && location.isEmpty()) return;
        showWeather();
        long now = SystemClock.elapsedRealtime();
        if (weatherInFlight || now < nextWeather) {
            updateForecast(location, latitude, longitude, now);
            return;
        }
        long connectionTime = weatherReadyAt;
        android.util.Log.i("BedsideClock", "Requesting weather observation");
        String address = stationAddress();
        weatherInFlight = true;
        nextWeather = now + (local ? 60_000L : WEATHER_INTERVAL);
        network.execute(() -> {
            BomWeather.Reading value;
            try {
                value = local ? PanTechWeather.temperature(address) : BomWeather.temperature(state, latitude, longitude);
            } catch (Exception error) {
                android.util.Log.w("BedsideClock", "Weather observation unavailable", error);
                value = null;
            }
            BomWeather.Reading result = value;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                weatherInFlight = false;
                if (weatherWifi == null || weatherReadyAt != connectionTime) {
                    nextWeather = 0;
                    updateWeather();
                    return;
                }
                if (local != preferences.getBoolean("usePanTech", true) || (local && !address.equals(stationAddress()))
                        || !location.equals(preferences.getString("locationKey", ""))) {
                    nextWeather = 0;
                    updateWeather();
                    return;
                }
                weatherFailed = result == null;
                if (!weatherFailed) {
                    weatherRetry = 5000L;
                    android.util.Log.i("BedsideClock", "Weather observation received");
                    lastReading = result;
                    weatherTime = SystemClock.elapsedRealtime();
                } else {
                    nextWeather = SystemClock.elapsedRealtime() + weatherRetry;
                    weatherRetry = Math.min(2 * 60_000L, weatherRetry * 2);
                }
                showWeather();
            });
        });
        updateForecast(location, latitude, longitude, now);
    }

    private void updateDays(String location, double latitude, double longitude) {
        if (location.isEmpty() || daysLoading) return;
        if (!daysLocation.equals(location)) {
            daysLocation = location;
            days = null;
            nextDays = 0;
        }
        if ((days == null || days.isCurrent()) && SystemClock.elapsedRealtime() < nextDays) return;
        daysLoading = true;
        network.execute(() -> {
            ForecastWeather.Outlook result;
            try {
                result = ForecastWeather.loadDays(latitude, longitude);
            } catch (Exception error) {
                android.util.Log.w("BedsideClock", "Weather outlook unavailable", error);
                result = null;
            }
            ForecastWeather.Outlook fetched = result;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                daysLoading = false;
                if (location.equals(preferences.getString("locationKey", ""))) {
                    days = fetched;
                    nextDays = SystemClock.elapsedRealtime() + (days == null ? 30_000L : FORECAST_INTERVAL);
                }
                renderWeatherPanel();
            });
        });
    }

    private void openWeatherPanel() {
        if (weatherPanel != null || settings != null) return;
        weatherPanel = new LinearLayout(this);
        weatherPanel.setOrientation(LinearLayout.VERTICAL);
        weatherPanel.setPadding(dp(24), dp(16), dp(24), dp(12));
        weatherPanel.setBackgroundColor(0xff111b24);
        weatherPanel.setOnClickListener(view -> closeWeatherPanel());
        root.addView(weatherPanel, new FrameLayout.LayoutParams(-1, -1));
        updateWeather();
        renderWeatherPanel();
        handler.postDelayed(dismissForecast, 30_000L);
    }

    private void renderWeatherPanel() {
        if (weatherPanel == null) return;
        weatherPanel.removeAllViews();
        TextView title = text(23);
        String locality = preferences.getString("locality", "");
        title.setText(locality.isEmpty() ? "Weather outlook" : locality + " · Weather outlook");
        weatherPanel.addView(title);
        if (days == null || !days.isCurrent() || !daysLocation.equals(preferences.getString("locationKey", ""))) {
            String status = locality.isEmpty() ? "Choose a locality in Weather settings."
                    : weatherWifi == null ? "Waiting for Wi-Fi."
                    : SystemClock.elapsedRealtime() < weatherReadyAt ? "Weather sync starts two minutes after Wi-Fi connects."
                    : daysLoading ? "Loading forecast…" : "Forecast unavailable.";
            weatherPanel.addView(textLabel(status));
            return;
        }
        LinearLayout row = new LinearLayout(this);
        weatherPanel.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        java.time.format.DateTimeFormatter dateFormat = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM");
        for (ForecastWeather.Day day : days.periods()) {
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(column, new LinearLayout.LayoutParams(0, -1, 1));
            column.addView(textLabel(day.date.equals(days.start) ? "Rest of today" : day.date.format(dateFormat)));
            TextView range = text(28);
            range.setText(day.temperatures);
            column.addView(range);
            column.addView(textLabel(day.condition == null ? "—" : day.condition.label));
            column.addView(textLabel("Rain chance  " + day.chance));
            column.addView(textLabel("Rainfall  " + day.rain));
        }
        weatherPanel.addView(textLabel("High / low in °C   ·   Peak rain chance   ·   Open-Meteo"));
    }

    private void closeWeatherPanel() {
        handler.removeCallbacks(dismissForecast);
        if (weatherPanel != null) root.removeView(weatherPanel);
        weatherPanel = null;
    }

    private void updateForecast(String location, double latitude, double longitude, long now) {
        if (location.isEmpty() || forecastInFlight || now < nextForecast) return;
        long connectionTime = weatherReadyAt;
        forecastInFlight = true;
        nextForecast = now + FORECAST_INTERVAL;
        network.execute(() -> {
            ForecastWeather fetched;
            try {
                fetched = ForecastWeather.load(latitude, longitude);
            } catch (Exception error) {
                android.util.Log.w("BedsideClock", "Hourly forecast unavailable", error);
                fetched = null;
            }
            ForecastWeather result = fetched;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                forecastInFlight = false;
                if (weatherWifi == null || weatherReadyAt != connectionTime || !location.equals(preferences.getString("locationKey", ""))) {
                    nextForecast = 0;
                    return;
                }
                long receivedAt = SystemClock.elapsedRealtime();
                if (result != null) {
                    forecastRetry = 5000L;
                    android.util.Log.i("BedsideClock", "Weather forecast received");
                    forecast = result;
                    forecastTime = receivedAt;
                } else {
                    nextForecast = receivedAt + forecastRetry;
                    forecastRetry = Math.min(15 * 60_000L, forecastRetry * 2);
                }
                showWeather();
            });
        });
    }

    private void showWeather() {
        String place = preferences.getString("locality", "");
        boolean local = preferences.getBoolean("usePanTech", true);
        boolean fresh = lastReading != null &&
                SystemClock.elapsedRealtime() - weatherTime < (local ? 3 * 60_000L : WEATHER_STALE);
        String value = fresh ? String.format(Locale.getDefault(), local ? "%.1f°C" : "%.0f°C", lastReading.temperature) : "—°C";
        setText(temperature, value);
        String details;
        if (local) details = fresh ? "PanTech outdoor temperature " + value : "PanTech outdoor temperature unavailable";
        else if (place.isEmpty()) details = "Outside temperature. Set postcode in Settings.";
        else if (!fresh) details = "Outside temperature unavailable. Bureau of Meteorology, " + place;
        else details = (weatherFailed ? "Last outside temperature " : "Outside temperature ")
                + value + ". Bureau of Meteorology, " + lastReading.station;
        ForecastWeather currentForecast = forecast != null &&
                SystemClock.elapsedRealtime() - forecastTime < FORECAST_STALE ? forecast : null;
        int color = temperature.getCurrentTextColor();
        if (currentForecast != displayedForecast || color != displayedForecastColor) {
            ForecastIcon icon = currentForecast == null ? null : new ForecastIcon(currentForecast, color);
            if (icon != null) icon.setBounds(0, 0, dp(19), dp(19));
            temperature.setCompoundDrawablesRelative(null, null, icon, null);
            displayedForecast = currentForecast;
            displayedForecastColor = color;
        }
        if (currentForecast != null) {
            details += ". Next few hours: " + currentForecast.condition.label + ". "
                    + (currentForecast.bom ? "BOM ACCESS via Open-Meteo." : "Open-Meteo forecast.");
        }
        if (!details.contentEquals(temperature.getContentDescription() == null ? "" : temperature.getContentDescription())) {
            temperature.setContentDescription(details);
        }
    }

    private void openSettings() {
        if (settings != null) return;
        settings = column();
        boolean landscapeLayout = getResources().getConfiguration().screenWidthDp >= 600;
        settings.setOrientation(landscapeLayout ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        settings.setBackgroundColor(SETTINGS_BACKGROUND);
        settings.setElevation(dp(8));
        settings.setFocusableInTouchMode(true);
        root.addView(settings, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(landscapeLayout ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(0xff262729);
        tabs.setPadding(dp(8), dp(8), dp(8), dp(8));
        if (landscapeLayout) {
            TextView navTitle = textLabel("Settings");
            navTitle.setPadding(dp(9), dp(5), 0, dp(8));
            tabs.addView(navTitle);
        }
        for (int index = 0; index < SETTINGS_TABS.length; index++) {
            int page = index;
            Button tab = settingsAction(SETTINGS_TABS[index], 0xff262729);
            tab.setMinWidth(0);
            tab.setMinimumWidth(0);
            tab.setGravity(landscapeLayout ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
            tab.setPadding(dp(landscapeLayout ? 12 : 3), 0, dp(3), 0);
            settingsTabs[index] = tab;
            LinearLayout.LayoutParams tabSlot = landscapeLayout
                    ? new LinearLayout.LayoutParams(-1, dp(44))
                    : new LinearLayout.LayoutParams(0, dp(44), 1);
            if (landscapeLayout) tabSlot.bottomMargin = dp(3);
            tabs.addView(tab, tabSlot);
            tab.setOnClickListener(view -> showSettingsPage(page));
        }
        settings.addView(tabs, new LinearLayout.LayoutParams(landscapeLayout ? dp(140) : -1,
                landscapeLayout ? -1 : -2));
        settingsBody = column();
        settings.addView(settingsBody, landscapeLayout
                ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(3));
        settingsTitle = text(23);
        settingsTitle.setTextColor(SETTINGS_TEXT);
        settingsTitle.setTypeface(null, Typeface.BOLD);
        header.addView(settingsTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button done = settingsAction("Done", 0xffd5d6d7);
        done.setMinWidth(dp(72));
        done.setTextColor(0xff171819);
        header.addView(done, new LinearLayout.LayoutParams(-2, dp(44)));
        done.setOnClickListener(view -> showClock());
        settingsBody.addView(header, new LinearLayout.LayoutParams(-1, -2));
        showSettingsPage(0);
        settings.requestFocus();
        updateBrightness();
        immersive();
    }

    private void showSettingsPage(int page) {
        settingsTab = page;
        settingsTitle.setText(SETTINGS_TABS[page]);
        settingsPreview = null;
        if (settingsContent != null) {
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            keyboard.hideSoftInputFromWindow(settingsContent.getWindowToken(), 0);
            settingsBody.removeView(settingsContent);
        }
        for (int index = 0; index < settingsTabs.length; index++) {
            Button tab = settingsTabs[index];
            boolean active = index == page;
            tab.setSelected(active);
            tab.setTextColor(active ? SETTINGS_TEXT : SETTINGS_SECONDARY);
            tab.setBackground(settingsBackground(active ? 0xff484a4d : 0xff262729));
        }
        settingsContent = new ScrollView(this);
        settingsContent.setFillViewport(true);
        LinearLayout panel = column();
        panel.setPadding(dp(16), dp(7), dp(16), dp(14));
        settingsContent.addView(panel);
        settingsBody.addView(settingsContent, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (page) {
            case 0: clockSettings(panel); break;
            case 1: brightnessSettings(panel); break;
            case 2: alarmSettings(panel); break;
            case 3: weatherSettings(panel); break;
            case 4: deviceSettings(panel); break;
        }
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private android.graphics.drawable.Drawable settingsBackground(int color) {
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33ffffff),
                rounded(color, 8), null);
    }

    private Button settingsAction(String label, int color) {
        Button action = button(label);
        action.setTextSize(14);
        action.setMinHeight(dp(44));
        action.setBackground(settingsBackground(color));
        return action;
    }

    private LinearLayout settingsCard() {
        LinearLayout card = column();
        card.setBackground(rounded(0xff28292b, 10));
        card.setPadding(dp(11), dp(7), dp(11), dp(7));
        return card;
    }

    private GradientDrawable clockBackground(int background, int solid) {
        if (background != 0) return rounded(background == 1 ? Color.BLACK : solid, 0);
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xff354557, 0xff241d36, 0xff33263e});
    }

    private void addSettingsColumns(LinearLayout panel, LinearLayout first, LinearLayout second) {
        if (getResources().getConfiguration().screenWidthDp < 600) {
            panel.addView(first);
            panel.addView(second);
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.addView(first, new LinearLayout.LayoutParams(0, -2, 1));
        View divider = new View(this);
        divider.setBackgroundColor(SETTINGS_BORDER);
        LinearLayout.LayoutParams dividerSpace = new LinearLayout.LayoutParams(dp(1), -1);
        dividerSpace.setMargins(dp(16), 0, dp(16), 0);
        row.addView(divider, dividerSpace);
        row.addView(second, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void clockSettings(LinearLayout panel) {
        LinearLayout faceOptions = column();
        LinearLayout details = column();
        addClockPreview(faceOptions);
        LinearLayout layoutOptions = new LinearLayout(this);
        LinearLayout style = column();
        LinearLayout format = column();
        layoutOptions.addView(style, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams formatSpace = new LinearLayout.LayoutParams(0, -2, 1);
        formatSpace.setMarginStart(dp(8));
        layoutOptions.addView(format, formatSpace);
        choice(style, "Style", "displayStyle", new String[] {"Digital", "Flip clock"}, 0);
        choice(format, "Time format", "timeFormat", new String[] {"Android setting", "12-hour", "24-hour"}, 0);
        faceOptions.addView(layoutOptions);
        slider(faceOptions, "Size", "size", 65, 120, 100);

        choice(details, "Font", "font", FONT_LABELS, 0);
        Button color = settingsAction("Set custom colour", 0xff3b3d40);
        Runnable showCustomColor = () -> color.setVisibility(
                preferences.getInt("background", 0) == 2 ? View.VISIBLE : View.GONE);
        choice(details, "Background", "background", new String[] {"Dusk gradient", "Black", "Custom solid"}, 0, showCustomColor);
        details.addView(color);
        color.setOnClickListener(view -> chooseColor());
        showCustomColor.run();
        choice(details, "Position", "position", new String[] {"Center", "Lower center", "Lower left"}, 0);
        Button visible = settingsAction("Show on clock", 0xff3b3d40);
        LinearLayout visibleOptions = column();
        visibleOptions.setVisibility(View.GONE);
        visible.setOnClickListener(view -> {
            boolean expanded = visibleOptions.getVisibility() == View.VISIBLE;
            visibleOptions.setVisibility(expanded ? View.GONE : View.VISIBLE);
            visible.setText(expanded ? "Show on clock" : "Hide display options");
        });
        details.addView(visible, new LinearLayout.LayoutParams(-1, dp(44)));
        toggle(visibleOptions, "Weekday", "showWeekday", true);
        toggle(visibleOptions, "Date", "showDate", true);
        toggle(visibleOptions, "Outside temperature", "showTemperature", true);
        details.addView(visibleOptions);
        addSettingsColumns(panel, faceOptions, details);
    }

    private void addClockPreview(LinearLayout panel) {
        FrameLayout preview = new FrameLayout(this);
        preview.setClipToOutline(true);
        preview.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(9));
            }
        });
        LinearLayout faceContent = column();
        faceContent.setGravity(Gravity.CENTER);
        LinearLayout digits = new LinearLayout(this);
        digits.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        TextView digital = text(30);
        digital.setIncludeFontPadding(false);
        digital.setSingleLine();
        FlipClockView flips = new FlipClockView(this);
        TextView suffix = text(10);
        suffix.setPadding(dp(4), 0, 0, 0);
        digits.addView(digital);
        digits.addView(flips);
        digits.addView(suffix);
        faceContent.addView(digits);
        TextView calendar = text(10);
        faceContent.addView(calendar);
        preview.addView(faceContent);
        TextView outside = text(10);
        FrameLayout.LayoutParams weatherPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        weatherPosition.setMargins(dp(7), dp(4), 0, 0);
        preview.addView(outside, weatherPosition);
        panel.addView(preview, new LinearLayout.LayoutParams(-1, dp(105)));
        settingsPreview = () -> {
            if (preview.getWidth() == 0) return;
            int background = preferences.getInt("background", 0);
            int solid = preferences.getInt("solid", 0xff18232d);
            boolean light = background == 2 && Color.luminance(solid) > .179;
            int ink = light ? 0xff151421 : 0xfff6f3ff;
            preview.setBackground(clockBackground(background, solid));
            outside.setTextColor(ink);
            outside.setText(temperature.getText());
            outside.setVisibility(preferences.getBoolean("showTemperature", true) ? View.VISIBLE : View.GONE);
            Typeface font = clockTypeface(preferences.getInt("font", 0));
            boolean flip = preferences.getInt("displayStyle", 0) == 1;
            boolean twentyFour = is24HourClock();
            Date now = new Date();
            String reading = DateFormat.format(twentyFour ? "HH:mm" : "h:mm", now).toString();
            String periodLabel = twentyFour ? "" : DateFormat.format("aa", now).toString();
            suffix.setText(periodLabel);
            suffix.setTextColor(ink);
            suffix.setVisibility(twentyFour ? View.GONE : View.VISIBLE);
            float available = preview.getWidth() - dp(16)
                    - (twentyFour ? 0 : suffix.getPaint().measureText(periodLabel) + suffix.getPaddingLeft());
            Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
            measure.setTypeface(font);
            measure.setTextSize(100);
            float unitWidth = flip ? FlipClockView.widthAtUnitSize(reading) : measure.measureText(reading) / 100f;
            float size = Math.min(dp(65), Math.max(1, available) / Math.max(1, unitWidth))
                    * Math.min(1f, preferences.getInt("size", 100) / 120f);
            digital.setVisibility(flip ? View.GONE : View.VISIBLE);
            flips.setVisibility(flip ? View.VISIBLE : View.GONE);
            if (flip) {
                flips.setFace(font, ink, light);
                flips.setDigitSize(size);
                flips.setReading(reading);
            } else {
                digital.setTypeface(font);
                digital.setTextColor(ink);
                digital.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
                digital.setText(reading);
            }
            boolean weekday = preferences.getBoolean("showWeekday", true);
            boolean showDate = preferences.getBoolean("showDate", true);
            calendar.setVisibility(weekday || showDate ? View.VISIBLE : View.GONE);
            calendar.setTextColor(ink);
            if (weekday || showDate) {
                String skeleton = weekday ? (showDate ? "EEEE MMMM d" : "EEEE") : "MMMM d";
                calendar.setText(DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), now));
            }
            int position = preferences.getInt("position", 0);
            int gravity = position == 0 ? Gravity.CENTER : Gravity.BOTTOM |
                    (position == 2 ? Gravity.LEFT : Gravity.CENTER_HORIZONTAL);
            FrameLayout.LayoutParams facePosition = new FrameLayout.LayoutParams(-2, -2, gravity);
            facePosition.setMargins(dp(8), 0, dp(8), position == 0 ? 0 : dp(6));
            faceContent.setLayoutParams(facePosition);
        };
        preview.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft && settingsPreview != null) settingsPreview.run();
        });
    }
    private void brightnessSettings(LinearLayout panel) {
        LinearLayout levels = column();
        LinearLayout wake = column();
        LinearLayout schedule = column();
        LinearLayout dayStart = column();
        LinearLayout nightOptions = column();
        LinearLayout manual = column();
        LinearLayout wakeTiming = column();
        LinearLayout sensorMinimum = column();
        TextView roomLightHint = textLabel("Room light follows the schedule on this device.");
        String[] modes = {"Schedule", "Room light", "Manual"};
        Button[] modeButtons = new Button[modes.length];
        LinearLayout modeRow = new LinearLayout(this);
        for (int index = 0; index < modes.length; index++) {
            modeButtons[index] = settingsAction(modes[index], 0xff28292b);
            modeButtons[index].setMinWidth(0);
            modeButtons[index].setMinimumWidth(0);
            modeButtons[index].setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (index > 0) slot.setMarginStart(dp(4));
            modeRow.addView(modeButtons[index], slot);
        }
        panel.addView(modeRow);
        Runnable refresh = () -> {
            int mode = preferences.getInt("brightnessMode", 0);
            boolean waking = preferences.getBoolean("wakeEnabled", false);
            boolean red = preferences.getBoolean("redAtNight", false);
            boolean scheduled = mode == 0 || (mode == 1 && lightSensor == null) || waking || red;
            schedule.setVisibility(scheduled || mode == 1 ? View.VISIBLE : View.GONE);
            dayStart.setVisibility(scheduled ? View.VISIBLE : View.GONE);
            nightOptions.setVisibility(scheduled ? View.VISIBLE : View.GONE);
            manual.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);
            wakeTiming.setVisibility(waking ? View.VISIBLE : View.GONE);
            sensorMinimum.setVisibility(mode == 1 && lightSensor != null ? View.VISIBLE : View.GONE);
            roomLightHint.setVisibility(mode == 1 && lightSensor == null ? View.VISIBLE : View.GONE);
            for (int index = 0; index < modeButtons.length; index++) {
                boolean selected = index == mode;
                modeButtons[index].setSelected(selected);
                modeButtons[index].setBackground(settingsBackground(selected ? 0xff484a4d : 0xff28292b));
            }
        };
        for (int index = 0; index < modeButtons.length; index++) {
            int mode = index;
            modeButtons[index].setOnClickListener(view -> {
                preferences.edit().putInt("brightnessMode", mode).apply();
                updateBrightness();
                refresh.run();
            });
        }
        heading(levels, "Screen levels");
        levels.addView(roomLightHint);
        slider(sensorMinimum, "Dark-room minimum brightness", "darkRoomMinimum", 0, 100,
                preferences.getInt("nightLevel", 10));
        levels.addView(sensorMinimum);
        scheduleRow(dayStart, "Day starts", "dayStart", 360);
        schedule.addView(dayStart);
        slider(schedule, "Day level", "dayLevel", 1, 100, 80);
        scheduleRow(nightOptions, "Night starts", "nightStart", 1320);
        slider(nightOptions, "Night level", "nightLevel", 0, 100, 10);
        schedule.addView(nightOptions);
        slider(manual, "Brightness", "manualLevel", 0, 100, 50);
        levels.addView(schedule);
        levels.addView(manual);
        levels.addView(textLabel("Close settings to see the selected brightness. At 0%, tap the black screen to reopen brightness settings."));
        toggle(levels, "Red clock at night", "redAtNight", false, refresh);
        heading(wake, "Wake brightness");
        toggle(wake, "Gradually brighten", "wakeEnabled", false, refresh);
        scheduleRow(wakeTiming, "Start at", "wakeStart", 360);
        choice(wakeTiming, "Brighten over", "wakeDuration",
                new String[] {"5 minutes", "10 minutes", "15 minutes", "30 minutes", "45 minutes", "60 minutes"}, 3);
        wake.addView(wakeTiming);
        wakeTiming.addView(textLabel("Brightens from your night level to your day level while the clock is open."));
        refresh.run();
        addSettingsColumns(panel, levels, wake);
    }

    private void alarmSettings(LinearLayout panel) {
        LinearLayout card = settingsCard();
        boolean enabled = DailyAlarm.enabled(this);
        Switch armed = new Switch(this);
        armed.setText("Next alarm");
        armed.setMinHeight(dp(44));
        armed.setChecked(enabled);
        card.addView(armed);
        armed.setOnCheckedChangeListener((view, checked) -> {
            if (!checked) {
                saveAlarm(false, DailyAlarm.time(this));
                return;
            }
            pickAlarmTime(() -> showSettingsPage(settingsTab));
        });
        if (enabled) {
            Button set = settingsAction("Time · " + formatMinute(DailyAlarm.time(this)), 0xff3b3d40);
            card.addView(set, new LinearLayout.LayoutParams(-1, dp(44)));
            set.setOnClickListener(view -> pickAlarmTime());
        }
        card.addView(textLabel("Rings once. Either volume button stops it on the alarm screen."));
        panel.addView(card);
    }

    private void weatherSettings(LinearLayout panel) {
        LinearLayout sensor = settingsCard();
        LinearLayout forecastOptions = settingsCard();
        LinearLayout station = column();
        Runnable showStation = () -> station.setVisibility(
                preferences.getBoolean("usePanTech", true) ? View.VISIBLE : View.GONE);
        heading(sensor, "Temperature source");
        toggle(sensor, "Use PanTech outdoor sensor", "usePanTech", true, () -> {
            showStation.run();
            refreshTemperature();
        });
        station.addView(textLabel("Station IP"));
        EditText address = new EditText(this);
        address.setSingleLine();
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        address.setContentDescription("PanTech IP address");
        address.setText(stationAddress());
        address.setSelectAllOnFocus(true);
        Button save = settingsAction("Save", 0xff3b3d40);
        addInputAction(station, address, save);
        sensor.addView(station);
        showStation.run();
        sensor.addView(textLabel("Switch off PanTech to use BOM observations."));
        save.setOnClickListener(view -> {
            String value = address.getText().toString().trim();
            if (value.contains(":") || !android.util.Patterns.IP_ADDRESS.matcher(value).matches()) {
                address.setError("Enter a valid IPv4 address");
                return;
            }
            preferences.edit().putString("panTechAddress", value).apply();
            if (preferences.getBoolean("usePanTech", true)) refreshTemperature();
            android.widget.Toast.makeText(this, "Station IP saved", android.widget.Toast.LENGTH_SHORT).show();
        });
        heading(forecastOptions, "Forecast location");
        EditText postcode = new EditText(this);
        postcode.setSingleLine();
        postcode.setInputType(InputType.TYPE_CLASS_NUMBER);
        postcode.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
        postcode.setHint("Australian postcode");
        postcode.setContentDescription("Australian postcode");
        postcode.setText(preferences.getString("postcode", ""));
        forecastOptions.addView(textLabel("Australian postcode"));
        TextView status = textLabel(preferences.getString("locality", ""));
        Button search = settingsAction("Find locality", 0xff3b3d40);
        addInputAction(forecastOptions, postcode, search);
        forecastOptions.addView(status);
        TextView forecastSource = textLabel("");
        forecastSource.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        forecastSource.setText("Forecast via https://open-meteo.com. Updates every two hours.");
        forecastOptions.addView(forecastSource);
        search.setOnClickListener(view -> findWeather(postcode.getText().toString(), search, status));
        addSettingsColumns(panel, sensor, forecastOptions);
    }

    private void addInputAction(LinearLayout panel, EditText field, Button action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams actionSlot = new LinearLayout.LayoutParams(-2, dp(44));
        actionSlot.setMarginStart(dp(6));
        row.addView(action, actionSlot);
        panel.addView(row);
    }

    private void deviceSettings(LinearLayout panel) {
        LinearLayout card = settingsCard();
        toggle(card, "Keep screen awake", "keepAwake", true);
        card.addView(textLabel("While the clock is open"));
        toggle(card, "Double-tap to exit", "doubleTap", true);
        card.addView(textLabel("Double-tap the clock face"));
        panel.addView(card);
    }

    private void findWeather(String postcode, Button search, TextView status) {
        if (!postcode.matches("[0-9]{4}")) {
            status.setText("Enter a four-digit Australian postcode.");
            return;
        }
        search.setEnabled(false);
        status.setText("Finding localities…");
        ScrollView currentSettings = settingsContent;
        network.execute(() -> {
            List<BomWeather.Locality> result;
            try {
                result = BomWeather.find(getAssets().open("au-postcodes.tsv"), postcode);
            } catch (Exception error) {
                android.util.Log.w("BedsideClock", "BOM locality lookup failed", error);
                result = null;
            }
            List<BomWeather.Locality> matches = result;
            runOnUiThread(() -> {
                if (isDestroyed() || settingsContent != currentSettings) return;
                search.setEnabled(true);
                if (matches == null) {
                    status.setText("Postcode lookup unavailable.");
                } else if (matches.isEmpty()) {
                    status.setText("No locality found for " + postcode + ".");
                } else {
                    status.setText(preferences.getString("locality", ""));
                    String[] names = matches.stream().map(BomWeather.Locality::toString).toArray(String[]::new);
                    new AlertDialog.Builder(this).setTitle("Choose locality")
                            .setItems(names, (dialog, index) -> {
                                BomWeather.Locality place = matches.get(index);
                                preferences.edit().putString("postcode", postcode).putString("locality", place.name)
                                        .putString("locationKey", postcode + ":" + place)
                                        .putString("weatherState", place.state)
                                        .putLong("latitude", Double.doubleToLongBits(place.latitude))
                                        .putLong("longitude", Double.doubleToLongBits(place.longitude)).apply();
                                lastReading = null;
                                weatherFailed = false;
                                nextWeather = 0;
                                forecast = null;
                                nextForecast = 0;
                                status.setText(place.name);
                                updateWeather();
                            }).setNegativeButton("Cancel", null).show();
                }
            });
        });
    }

    private void chooseColor() {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(String.format(Locale.ROOT, "#%06X", preferences.getInt("solid", 0xff18232d) & 0xffffff));
        input.setContentDescription("Hex color");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Solid color · #RRGGBB")
                .setView(input).setPositiveButton("Apply", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(view -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String value = input.getText().toString().trim();
            if (!value.matches("#[0-9a-fA-F]{6}")) {
                input.setError("Enter a color such as #18232D");
                return;
            }
            preferences.edit().putInt("solid", Color.parseColor(value)).putInt("background", 2).apply();
            dialog.dismiss();
            showSettingsPage(settingsTab);
        }));
        dialog.show();
    }

    private void heading(LinearLayout panel, String title) {
        TextView label = text(17);
        label.setText(title);
        label.setTextColor(SETTINGS_TEXT);
        label.setPadding(0, dp(6), 0, dp(5));
        label.setTypeface(null, Typeface.BOLD);
        panel.addView(label);
    }

    private void choice(LinearLayout panel, String label, String key, String[] options, int fallback) {
        choice(panel, label, key, options, fallback, null);
    }

    private void choice(LinearLayout panel, String label, String key, String[] options, int fallback, Runnable onChange) {
        panel.addView(textLabel(label));
        Spinner select = new Spinner(this);
        select.setContentDescription(label);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        select.setAdapter(adapter);
        select.setSelection(preferences.getInt(key, fallback));
        select.setMinimumHeight(dp(38));
        select.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                preferences.edit().putInt(key, index).apply();
                updateBrightness();
                if (onChange != null) onChange.run();
                if (settingsPreview != null) settingsPreview.run();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        panel.addView(select);
    }

    private void slider(LinearLayout panel, String caption, String key, int min, int max, int fallback) {
        int value = preferences.getInt(key, fallback);
        TextView label = textLabel(caption + " · " + value + "%");
        panel.addView(label);
        SeekBar slider = new SeekBar(this);
        slider.setContentDescription(caption);
        slider.setMin(min);
        slider.setMax(max);
        slider.setProgress(value);
        slider.setMinimumHeight(dp(34));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                if (!fromUser) return;
                label.setText(caption + " · " + progress + "%");
                preferences.edit().putInt(key, progress).apply();
                updateBrightness();
                if (settingsPreview != null) settingsPreview.run();
            }

            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {}
        });
        panel.addView(slider);
    }

    private void scheduleRow(LinearLayout panel, String caption, String key, int fallback) {
        int minute = preferences.getInt(key, fallback);
        Button change = button(caption + " · " + formatMinute(minute));
        panel.addView(change);
        change.setOnClickListener(view -> {
            int current = preferences.getInt(key, fallback);
            new TimePickerDialog(this, (picker, hour, minutes) -> {
                int selected = hour * 60 + minutes;
                boolean matchesOtherBoundary = (key.equals("dayStart") && selected == preferences.getInt("nightStart", 1320))
                        || (key.equals("nightStart") && selected == preferences.getInt("dayStart", 360));
                if (matchesOtherBoundary) {
                    Toast.makeText(this, "Choose different times for day and night.", Toast.LENGTH_SHORT).show();
                    return;
                }
                preferences.edit().putInt(key, selected).apply();
                change.setText(caption + " · " + formatMinute(selected));
                updateBrightness();
            }, current / 60, current % 60, is24HourClock()).show();
        });
    }

    private String formatMinute(int minute) {
        return java.time.LocalTime.of(minute / 60, minute % 60).format(java.time.format.DateTimeFormatter.ofPattern(
                is24HourClock() ? "HH:mm" : "h:mm a", Locale.getDefault()));
    }

    private boolean is24HourClock() {
        int format = preferences.getInt("timeFormat", 0);
        return format == 2 || (format == 0 && DateFormat.is24HourFormat(this));
    }

    private String alarmSummary() {
        return DailyAlarm.enabled(this) ? "Alarm · " + formatMinute(DailyAlarm.time(this)) : "Alarm · Off";
    }

    private void openAlarm() {
        int minute = DailyAlarm.time(this);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("Alarm")
                .setMessage(DailyAlarm.enabled(this)
                        ? "Set for " + formatMinute(minute) + ". Rings once."
                        : "Choose a time for the next alarm.")
                .setPositiveButton("Set time", (ignored, which) -> pickAlarmTime())
                .setNegativeButton("Cancel", null);
        if (DailyAlarm.enabled(this)) {
            dialog.setNeutralButton("Turn off", (ignored, which) -> saveAlarm(false, minute));
        }
        dialog.show();
    }

    private void pickAlarmTime() {
        pickAlarmTime(null);
    }

    private void pickAlarmTime(Runnable cancelled) {
        int minute = DailyAlarm.time(this);
        TimePickerDialog picker = new TimePickerDialog(this, (view, hour, minutes) -> saveAlarm(true, hour * 60 + minutes),
                minute / 60, minute % 60, is24HourClock());
        if (cancelled != null) {
            picker.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", (dialog, which) -> picker.cancel());
            picker.setOnCancelListener(dialog -> cancelled.run());
        }
        picker.show();
    }

    private void saveAlarm(boolean enabled, int minute) {
        DailyAlarm.save(this, enabled, minute);
        if (settings != null) showSettingsPage(settingsTab);
        updateAlarm();
    }

    private void updateAlarm() {
        alarmBell.setVisibility(DailyAlarm.enabled(this) ? View.VISIBLE : View.GONE);
        String summary = alarmSummary();
        if (!summary.contentEquals(alarmBell.getContentDescription() == null ? "" : alarmBell.getContentDescription())) {
            alarmBell.setContentDescription(summary);
        }
        if (AlarmSound.isRinging() && ringingDialog == null && !isFinishing()) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            ringingDialog = new AlertDialog.Builder(this).setTitle("Bedside alarm")
                    .setMessage("Tap Stop alarm or press either volume button.")
                    .setCancelable(false)
                    .setOnKeyListener((dialog, key, event) -> handleAlarmVolumeKey(event))
                    .setPositiveButton("Stop alarm", (dialog, which) ->
                            stopService(new Intent(this, AlarmSound.class))).create();
            ringingDialog.show();
        } else if (!AlarmSound.isRinging() && ringingDialog != null) {
            ringingDialog.dismiss();
            ringingDialog = null;
            setShowWhenLocked(false);
            setTurnScreenOn(false);
            if (!preferences.getBoolean("keepAwake", true)) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleAlarmVolumeKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean handleAlarmVolumeKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && stoppingWithVolumeKey) {
            stoppingWithVolumeKey = false;
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || (!AlarmSound.isRinging() && !stoppingWithVolumeKey)) {
            return false;
        }
        if (!stoppingWithVolumeKey) {
            stoppingWithVolumeKey = true;
            stopService(new Intent(this, AlarmSound.class));
        }
        return true;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAlarmIntent(intent);
    }

    private void handleAlarmIntent(Intent intent) {
        if (DailyAlarm.SHOW.equals(intent.getAction())) openAlarm();
        if (DailyAlarm.RING.equals(intent.getAction())) updateAlarm();
    }

    private void toggle(LinearLayout panel, String title, String key, boolean fallback) {
        toggle(panel, title, key, fallback, null);
    }

    private void toggle(LinearLayout panel, String title, String key, boolean fallback, Runnable onChange) {
        Switch control = new Switch(this);
        control.setText(title);
        control.setChecked(preferences.getBoolean(key, fallback));
        control.setMinHeight(dp(44));
        control.setOnCheckedChangeListener((view, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            updateBrightness();
            if (onChange != null) onChange.run();
            if (settingsPreview != null) settingsPreview.run();
        });
        panel.addView(control);
    }

    @Override public void onBackPressed() {
        if (weatherPanel != null) closeWeatherPanel();
        else if (settings != null) showClock();
        else super.onBackPressed();
    }

    private TextView text(float size) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(0xfff6f3ff);
        return view;
    }

    private TextView textLabel(String label) {
        TextView view = text(14);
        view.setText(label);
        view.setTextColor(SETTINGS_SECONDARY);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
