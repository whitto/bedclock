package au.clock.display;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

public final class AlarmSound extends Service {
    private static final String CHANNEL = "bedside_alarm";
    private static final String DISMISS = "au.clock.display.DISMISS_ALARM";
    private static final int NOTIFICATION = 100;
    private static final long MAX_RING_TIME = 10 * 60_000L;
    private static boolean ringing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            stopSelf();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    static boolean isRinging() {
        return ringing;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && DISMISS.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Bedside alarms",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        notifications.createNotificationChannel(channel);
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, AlarmSound.class).setAction(DISMISS), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent show = DailyAlarm.clockIntent(this, DailyAlarm.RING);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.alarm_bell).setContentTitle("Bedside alarm")
                .setCategory(Notification.CATEGORY_ALARM).setOngoing(true)
                .setContentIntent(show).setFullScreenIntent(show, true)
                .addAction(new Notification.Action.Builder(null, "Stop alarm", stop).build()).build();
        startForeground(NOTIFICATION, notification);
        if (!ringing) {
            ringing = true;
            AudioAttributes audio = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            player = MediaPlayer.create(this, R.raw.alarm_tone, audio, 0);
            if (player != null) {
                player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
                player.setLooping(true);
                player.start();
            }
            handler.postDelayed(this::stopSelf, MAX_RING_TIME);
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        unregisterReceiver(screenOff);
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        ringing = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
