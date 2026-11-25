package au.clock.display;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

/** Connect to the installed player without opening its UI or requesting playback. */
final class SpotifyLink {
    static final String PACKAGE = "com.spotify.music";
    private final Activity clock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaBrowser browser;
    private MediaController controller;
    private final android.os.PowerManager.WakeLock playbackScreen;
    private boolean watching, played;
    private long idleSince;

    SpotifyLink(Activity clock) {
        this.clock = clock;
        android.os.PowerManager power = clock.getSystemService(android.os.PowerManager.class);
        playbackScreen = power.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK, "BedsideClock:Spotify");
        playbackScreen.setReferenceCounted(false);
    }

    void connect() {
        Intent browse = new Intent("android.media.browse.MediaBrowserService").setPackage(PACKAGE);
        ResolveInfo service = clock.getPackageManager().resolveService(browse, 0);
        if (service == null) return;
        ComponentName name = new ComponentName(PACKAGE, service.serviceInfo.name);
        browser = new MediaBrowser(clock, name, new MediaBrowser.ConnectionCallback() {
            @Override public void onConnected() {
                controller = new MediaController(clock, browser.getSessionToken());
            }

            @Override public void onConnectionSuspended() { controller = null; }
            @Override public void onConnectionFailed() { controller = null; }
        }, null);
        browser.connect();
    }

    void open() {
        Intent player = clock.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (player == null) {
            Toast.makeText(clock, "Spotify is not installed", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!android.provider.Settings.canDrawOverlays(clock)) {
            new android.app.AlertDialog.Builder(clock)
                    .setTitle("Return to clock after Spotify")
                    .setMessage("Allow Bedside Clock to display over other apps so it can return after playback stops.")
                    .setPositiveButton("Open settings", (dialog, which) -> clock.startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + clock.getPackageName()))))
                    .setNegativeButton("Just open Spotify", (dialog, which) -> clock.startActivity(player))
                    .show();
            return;
        }
        watching = true;
        if (!playbackScreen.isHeld()) playbackScreen.acquire();
        played = false;
        idleSince = 0;
        player.setFlags(player.getFlags() & ~(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        clock.startActivity(player);
        handler.removeCallbacks(check);
        handler.post(check);
    }

    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (!watching) return;
            PlaybackState state = controller == null ? null : controller.getPlaybackState();
            int code = state == null ? PlaybackState.STATE_NONE : state.getState();
            boolean playing = code == PlaybackState.STATE_PLAYING;
            if (code == PlaybackState.STATE_NONE || code == PlaybackState.STATE_ERROR) {
                // Some Spotify builds limit session access to approved media browsers.
                AudioManager audio = (AudioManager) clock.getSystemService(Context.AUDIO_SERVICE);
                playing = audio.isMusicActive();
            }
            if (playing) played = true;
            if (playing || code == PlaybackState.STATE_BUFFERING || code == PlaybackState.STATE_CONNECTING
                    || code == PlaybackState.STATE_SKIPPING_TO_NEXT || code == PlaybackState.STATE_SKIPPING_TO_PREVIOUS) {
                idleSince = 0;
            } else if (played) {
                long now = SystemClock.elapsedRealtime();
                if (idleSince == 0) idleSince = now;
                if (now - idleSince >= 5000) {
                    Intent home = new Intent(clock, ClockActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    clock.startActivity(home);
                    stopWatching();
                    return;
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    void stopWatching() {
        watching = false;
        if (playbackScreen.isHeld()) playbackScreen.release();
        handler.removeCallbacks(check);
    }

    void close() {
        stopWatching();
        if (browser != null) browser.disconnect();
    }
}
