package au.clock.display;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextClock;

public final class ClockDream extends DreamService {
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setFullscreen(true);
        setInteractive(false);
        setScreenBright(false);

        SharedPreferences settings = getSharedPreferences("ClockActivity", MODE_PRIVATE);
        LinearLayout face = new LinearLayout(this);
        face.setBackgroundColor(Color.BLACK);
        face.setOrientation(LinearLayout.VERTICAL);
        face.setGravity(Gravity.CENTER);
        int format = settings.getInt("timeFormat", 0);
        boolean use24 = format == 2 || (format == 0 && DateFormat.is24HourFormat(this));
        face.addView(clock(use24 ? "HH:mm" : "h:mm a", 90));
        boolean weekday = settings.getBoolean("showWeekday", true);
        boolean date = settings.getBoolean("showDate", true);
        if (weekday || date) {
            String skeleton = weekday ? (date ? "EEEEMMMMd" : "EEEE") : "MMMMd";
            face.addView(clock(DateFormat.getBestDateTimePattern(
                    getResources().getConfiguration().getLocales().get(0), skeleton), 22));
        }
        setContentView(face);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (!android.provider.Settings.canDrawOverlays(this)) return;
        android.content.Intent clock = new android.content.Intent(this, ClockActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        wakeUp();
        startActivity(clock);
    }

    private TextClock clock(String pattern, int size) {
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour(pattern);
        clock.setFormat24Hour(pattern);
        clock.setTextSize(size);
        clock.setTextColor(Color.LTGRAY);
        return clock;
    }
}
