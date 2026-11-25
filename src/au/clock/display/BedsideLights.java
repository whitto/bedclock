package au.clock.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BedsideLights {
    private static final int AMBER = 0xffffbd47;
    private static final int CARD = 0xff303133;
    private static final int MUTED = 0xffbebfc1;
    private static final String[] NAMES = {"Lamp Craig", "Lamp Kristen"};
    private final Activity activity;
    private final SharedPreferences preferences;
    private static final long REFRESH_MS = 2000;
    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Dialog lampsDialog;
    private View dimCover;
    private int displayLevel = -1;

    void setDisplayLevel(int level) {
        displayLevel = level;
        if (lampsDialog == null || level < 0) return;
        WindowManager.LayoutParams attributes = lampsDialog.getWindow().getAttributes();
        attributes.screenBrightness = level / 100f;
        lampsDialog.getWindow().setAttributes(attributes);
        dimCover.setVisibility(level < 5 ? View.VISIBLE : View.GONE);
        if (level < 5) dimCover.setBackgroundColor(Color.argb(255 - level * 51, 0, 0, 0));
        dimCover.setClickable(level == 0);
    }

    BedsideLights(Activity activity) {
        this.activity = activity;
        preferences = activity.getSharedPreferences("bedsideLights", Activity.MODE_PRIVATE);
    }

    void close() {
        requests.shutdownNow();
        reads.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView label(String text, int size, int color) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        return label;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView action(String title, String description, Runnable perform) {
        TextView control = label(title, 24, Color.WHITE);
        control.setGravity(Gravity.CENTER);
        control.setBackground(rounded(0xff494a4c, 28));
        control.setContentDescription(description);
        control.setFocusable(true);
        control.setOnClickListener(view -> perform.run());
        return control;
    }

    void open() {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        LinearLayout screen = column();
        screen.setBackgroundColor(Color.BLACK);
        screen.setPadding(dp(18), dp(14), dp(18), dp(18));
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("Bedside lamps", 30, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView settings = action("⚙", "Home Assistant connection", () -> {
            dialog.dismiss();
            connection();
        });
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView close = action("×", "Close bedside lamps", dialog::dismiss);
        LinearLayout.LayoutParams closeSize = new LinearLayout.LayoutParams(dp(48), dp(48));
        closeSize.setMarginStart(dp(10));
        header.addView(close, closeSize);
        screen.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout panel = new LinearLayout(activity);
        LinearLayout.LayoutParams cards = new LinearLayout.LayoutParams(-1, 0, 1);
        cards.topMargin = dp(16);
        screen.addView(panel, cards);
        FrameLayout content = new FrameLayout(activity);
        content.addView(screen);
        lampsDialog = dialog;
        dimCover = new View(activity);
        dimCover.setOnClickListener(view -> dialog.dismiss());
        content.addView(dimCover);
        dialog.setContentView(content);
        setDisplayLevel(displayLevel);
        LampPanel lamps = preferences.getString("token", "").isEmpty() ? null : new LampPanel(dialog, panel);
        dialog.setOnDismissListener(ignored -> {
            if (lamps != null) ui.removeCallbacks(lamps.refreshTask);
            if (lampsDialog == dialog) {
                lampsDialog = null;
                dimCover = null;
            }
        });
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dialog.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        if (lamps == null) {
            panel.addView(label("Open Connection to link Home Assistant.", 16, MUTED));
            return;
        }
        lamps.refresh();
    }

    private void connection() {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText server = new EditText(activity);
        server.setSingleLine();
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setHint("Home Assistant URL");
        server.setText(preferences.getString("server", "http://10.0.1.25:8123"));
        form.addView(server);
        EditText token = new EditText(activity);
        token.setSingleLine();
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint("Long-lived access token");
        form.addView(token);
        new AlertDialog.Builder(activity).setTitle("Home Assistant connection").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", (dialog, which) -> {
                    String address = server.getText().toString().trim().replaceAll("/+$", "");
                    String key = token.getText().toString().trim();
                    SharedPreferences.Editor changes = preferences.edit().putString("server", address);
                    if (!key.isEmpty()) changes.putString("token", key);
                    changes.apply();
                    open();
                }).show();
    }

    private JSONObject findLamp(JSONArray states, String name) throws Exception {
        JSONObject match = null;
        for (int i = 0; i < states.length(); i++) {
            JSONObject entity = states.getJSONObject(i);
            if (!entity.getString("entity_id").startsWith("light.")) continue;
            if (!name.equalsIgnoreCase(entity.getJSONObject("attributes").optString("friendly_name"))) continue;
            if (match != null) throw new IllegalStateException("Duplicate light name");
            match = entity;
        }
        if (match == null) {
            throw new IllegalStateException("Light unavailable");
        }
        return match;
    }

    private final class LampPanel {
        private final Dialog dialog;
        private final Lamp[] lamps = new Lamp[NAMES.length];
        private final Runnable refreshTask = this::refresh;

        LampPanel(Dialog dialog, LinearLayout panel) {
            this.dialog = dialog;
            for (int index = 0; index < lamps.length; index++) {
                lamps[index] = new Lamp(panel, NAMES[index]);
            }
        }

        void refresh() {
            if (!dialog.isShowing() || reads.isShutdown()) return;
            String[] ids = new String[lamps.length];
            int[] revisions = new int[lamps.length];
            for (int index = 0; index < lamps.length; index++) {
                Lamp lamp = lamps[index];
                ids[index] = lamp.id;
                revisions[index] = lamp.revision;
            }
            reads.execute(() -> {
                LampState[] states = new LampState[lamps.length];
                JSONArray all = null;
                boolean discoveryTried = false;
                for (int index = 0; index < states.length; index++) {
                    try {
                        if (ids[index] != null) {
                            states[index] = readLamp(ids[index]);
                        } else {
                            if (!discoveryTried) {
                                discoveryTried = true;
                                all = (JSONArray) request("/api/states", null);
                            }
                            if (all != null) states[index] = decodeLamp(findLamp(all, NAMES[index]));
                        }
                    } catch (Exception error) {
                        android.util.Log.d("BedsideClock", "Could not refresh " + NAMES[index]);
                    }
                }
                ui.post(() -> {
                    if (!dialog.isShowing() || reads.isShutdown()) return;
                    for (int index = 0; index < lamps.length; index++) {
                        Lamp lamp = lamps[index];
                        if (!lamp.busy && !lamp.dragging && lamp.revision == revisions[index]) {
                            lamp.apply(states[index]);
                        }
                    }
                    ui.postDelayed(refreshTask, REFRESH_MS);
                });
            });
        }

        private final class Lamp {
            final String name;
            final TextView bulb;
            final TextView power;
            final TextView status;
            final TextView feedback;
            final TextView error;
            final SeekBar dimmer;
            String id;
            LampState state;
            int revision;
            boolean busy;
            boolean dragging;

            Lamp(LinearLayout panel, String name) {
                this.name = name;
                LinearLayout card = column();
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(16), dp(14), dp(16), dp(14));
                card.setBackground(rounded(CARD, 24));
                LinearLayout.LayoutParams tile = new LinearLayout.LayoutParams(0, -1, 1);
                if (panel.getChildCount() > 0) tile.setMarginStart(dp(12));
                panel.addView(card, tile);
                LinearLayout heading = new LinearLayout(activity);
                heading.setGravity(Gravity.CENTER_VERTICAL);
                bulb = label("💡", 25, AMBER);
                heading.addView(bulb, new LinearLayout.LayoutParams(dp(42), -2));
                LinearLayout labels = column();
                TextView title = label(name, 23, Color.WHITE);
                title.setTypeface(null, Typeface.BOLD);
                labels.addView(title);
                status = label("", 17, MUTED);
                labels.addView(status);
                heading.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                power = action("", name, () -> {
                    if (state != null && state.available) change(!state.on, -1);
                });
                power.setForeground(activity.getDrawable(R.drawable.bedside_power).mutate());
                power.setForegroundGravity(Gravity.CENTER);
                heading.addView(power, new LinearLayout.LayoutParams(dp(56), dp(56)));
                card.addView(heading, new LinearLayout.LayoutParams(-1, -2));

                feedback = label("", 17, Color.WHITE);
                feedback.setPadding(0, dp(12), 0, 0);
                card.addView(feedback);
                dimmer = new SeekBar(activity);
                dimmer.setContentDescription(name + " brightness");
                dimmer.setThumb(null);
                dimmer.setPadding(0, 0, 0, 0);
                LayerDrawable track = new LayerDrawable(new android.graphics.drawable.Drawable[] {
                        rounded(0xff535456, 20),
                        new ClipDrawable(rounded(AMBER, 20), Gravity.LEFT, ClipDrawable.HORIZONTAL)
                });
                track.setId(0, android.R.id.background);
                track.setId(1, android.R.id.progress);
                dimmer.setProgressDrawable(track);
                card.addView(dimmer, new LinearLayout.LayoutParams(-1, dp(44)));
                error = label("", 14, MUTED);
                error.setVisibility(View.GONE);
                card.addView(error);

                dimmer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                        if (!user) return;
                        feedback.setText(value + "%");
                        if (!dragging) change(value > 0, value);
                    }
                    @Override public void onStartTrackingTouch(SeekBar bar) {
                        dragging = true;
                        power.setEnabled(false);
                    }
                    @Override public void onStopTrackingTouch(SeekBar bar) {
                        dragging = false;
                        int value = bar.getProgress();
                        change(value > 0, value);
                    }
                });
                apply(null);
                status.setText("Loading…");
            }

            void apply(LampState latest) {
                state = latest;
                if (latest != null) id = latest.id;
                boolean available = latest != null && latest.available;
                boolean on = available && latest.on;
                boolean dimmable = latest != null ? latest.dimmable : dimmer.getVisibility() == View.VISIBLE;
                status.setText(!available ? "Unavailable" : on ? "On" : "Off");
                status.setTextColor(on ? AMBER : MUTED);
                bulb.setAlpha(on ? 1f : 0.35f);
                power.getForeground().setTint(on ? 0xff202122 : Color.WHITE);
                power.setBackground(rounded(on ? 0xffffad1a : 0xff565759, 28));
                power.setContentDescription(!available ? name + " unavailable" : (on ? "Turn off " : "Turn on ") + name);
                power.setEnabled(available);
                int level = available ? latest.level : -1;
                dimmer.setProgress(Math.max(0, level));
                feedback.setText(level < 0 ? "—" : level + "%");
                dimmer.setEnabled(available && dimmable);
                dimmer.setVisibility(dimmable ? View.VISIBLE : View.GONE);
                feedback.setVisibility(dimmable ? View.VISIBLE : View.GONE);
            }

            void change(boolean turnOn, int brightness) {
                if (busy || state == null || !state.available || requests.isShutdown()) return;
                String entityId = id;
                busy = true;
                revision++;
                power.setEnabled(false);
                dimmer.setEnabled(false);
                error.setVisibility(View.GONE);
                status.setText("Updating…");
                requests.execute(() -> {
                    LampState latest = null;
                    try {
                        JSONObject data = new JSONObject().put("entity_id", entityId);
                        if (turnOn && brightness > 0) data.put("brightness_pct", brightness);
                        request("/api/services/light/" + (turnOn ? "turn_on" : "turn_off"), data);
                        latest = LampState.confirm(() -> readLamp(entityId), turnOn, brightness);
                    } catch (Exception failure) {
                        if (!(failure instanceof InterruptedException)) {
                            try { latest = readLamp(entityId); } catch (Exception ignored) {}
                        }
                    }
                    LampState result = latest;
                    ui.post(() -> {
                        if (!dialog.isShowing() || requests.isShutdown()) return;
                        busy = false;
                        revision++;
                        apply(result);
                        error.setVisibility(result != null && result.matches(turnOn, brightness)
                                ? View.GONE : View.VISIBLE);
                        error.setText("Change not confirmed by Home Assistant.");
                    });
                });
            }
        }
    }

    private LampState readLamp(String id) throws Exception {
        return decodeLamp((JSONObject) request("/api/states/" + id, null));
    }

    private LampState decodeLamp(JSONObject entity) throws Exception {
        JSONObject attributes = entity.getJSONObject("attributes");
        JSONArray modes = attributes.optJSONArray("supported_color_modes");
        boolean dimmable = !attributes.isNull("brightness");
        if (modes != null) {
            for (int i = 0; i < modes.length(); i++) {
                String mode = modes.optString(i);
                dimmable |= !mode.isEmpty() && !"onoff".equals(mode) && !"unknown".equals(mode);
            }
        }
        return new LampState(entity.getString("entity_id"), entity.getString("state"),
                attributes.optInt("brightness", -1), dimmable);
    }

    private Object request(String path, JSONObject data) throws Exception {
        URL url = new URL(preferences.getString("server", "") + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + preferences.getString("token", ""));
        try {
            if (data != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(data.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return new JSONTokener(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).nextValue();
            }
        } finally {
            connection.disconnect();
        }
    }
}
