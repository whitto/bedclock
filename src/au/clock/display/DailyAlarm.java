package au.clock.display;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.time.ZonedDateTime;

public final class DailyAlarm extends BroadcastReceiver {
    private static final String FIRE = "au.clock.display.FIRE_ALARM";
    static final String SHOW = "au.clock.display.SHOW_ALARM";
    static final String RING = "au.clock.display.SHOW_RINGING_ALARM";
    private static final int DEFAULT_TIME = 7 * 60;

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("dailyAlarm", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return preferences(context).getBoolean("enabled", false);
    }

    static int time(Context context) {
        return preferences(context).getInt("minutes", DEFAULT_TIME);
    }

    static void save(Context context, boolean enabled, int minutes) {
        preferences(context).edit().putBoolean("enabled", enabled).putInt("minutes", minutes)
                .putLong("triggerAt", enabled ? nextOccurrence(minutes) : 0).apply();
        schedule(context);
    }

    private static long nextOccurrence(int minutes) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = now.toLocalDate().atTime(minutes / 60, minutes % 60).atZone(now.getZone());
        if (!next.isAfter(now)) {
            next = now.toLocalDate().plusDays(1).atTime(minutes / 60, minutes % 60).atZone(now.getZone());
        }
        return next.toInstant().toEpochMilli();
    }

    static void schedule(Context context) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        Intent fire = new Intent(context, DailyAlarm.class).setAction(FIRE);
        PendingIntent operation = PendingIntent.getBroadcast(context, 0, fire,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (!enabled(context)) {
            manager.cancel(operation);
            return;
        }
        SharedPreferences saved = preferences(context);
        long triggerAt = saved.getLong("triggerAt", 0);
        if (triggerAt == 0) {
            // An alarm saved before one-shot scheduling had only a time of day.
            triggerAt = nextOccurrence(time(context));
            saved.edit().putLong("triggerAt", triggerAt).apply();
        }
        if (triggerAt <= System.currentTimeMillis()) {
            // A due operation may already be queued for delivery.
            return;
        }
        manager.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt,
                clockIntent(context, SHOW)), operation);
    }

    static PendingIntent clockIntent(Context context, String action) {
        Intent clock = new Intent(context, ClockActivity.class).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, clock, PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!enabled(context)) return;
        if (FIRE.equals(intent.getAction())) {
            long triggerAt = preferences(context).getLong("triggerAt", 0);
            if (triggerAt > System.currentTimeMillis()) return;
            save(context, false, time(context));
            context.startForegroundService(new Intent(context, AlarmSound.class));
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && preferences(context).contains("triggerAt")
                && preferences(context).getLong("triggerAt", 0) <= System.currentTimeMillis()) {
            save(context, false, time(context));
        } else {
            schedule(context);
        }
    }
}
