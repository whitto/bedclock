package au.clock.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.content.SharedPreferences;
import android.text.InputType;
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
    private static final int CARD = 0xff382b24;
    private static final int MUTED = 0xffc2b5ad;
    private static final String[] NAMES = {"Lamp Craig", "Lamp Kristen"};
    private final Activity activity;
    private final SharedPreferences preferences;
    private final ExecutorService requests = Executors.newSingleThreadExecutor();

    BedsideLights(Activity activity) {
        this.activity = activity;
        preferences = activity.getSharedPreferences("bedsideLights", Activity.MODE_PRIVATE);
    }

    void close() {
        requests.shutdownNow();
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
        control.setBackground(rounded(0xff564943, 28));
        control.setContentDescription(description);
        control.setFocusable(true);
        control.setOnClickListener(view -> perform.run());
        return control;
    }

    void open() {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        LinearLayout screen = column();
        screen.setBackgroundColor(0xff211914);
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
        dialog.setContentView(screen);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dialog.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        TextView status = label("Loading lamps…", 16, MUTED);
        panel.addView(status);
        if (preferences.getString("token", "").isEmpty()) {
            status.setText("Open Connection to link Home Assistant.");
            return;
        }
        requests.execute(() -> {
            try {
                JSONArray states = (JSONArray) request("/api/states", null);
                activity.runOnUiThread(() -> {
                    if (!dialog.isShowing()) return;
                    panel.removeAllViews();
                    for (String name : NAMES) {
                        try {
                            addLamp(panel, name, findLamp(states, name));
                        } catch (Exception error) {
                            TextView missing = label(name + " is unavailable.", 17, MUTED);
                            panel.addView(missing);
                        }
                    }
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> status.setText("Home Assistant connection failed. Check Connection."));
            }
        });
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
        if (match == null || !("on".equals(match.optString("state")) || "off".equals(match.optString("state")))) {
            throw new IllegalStateException("Light unavailable");
        }
        return match;
    }

    private void addLamp(LinearLayout panel, String name, JSONObject entity) throws Exception {
        String id = entity.getString("entity_id");
        JSONObject attributes = entity.getJSONObject("attributes");
        boolean[] on = {"on".equals(entity.getString("state"))};
        LinearLayout card = column();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(CARD, 24));
        LinearLayout.LayoutParams tile = new LinearLayout.LayoutParams(0, -1, 1);
        if (panel.getChildCount() > 0) tile.setMarginStart(dp(12));
        panel.addView(card, tile);
        LinearLayout heading = new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView bulb = label("💡", 25, AMBER);
        bulb.setAlpha(on[0] ? 1f : 0.35f);
        heading.addView(bulb, new LinearLayout.LayoutParams(dp(42), -2));
        LinearLayout labels = column();
        TextView title = label(name, 23, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        labels.addView(title);
        TextView stateLabel = label("", 17, MUTED);
        labels.addView(stateLabel);
        heading.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView toggle = action("⏻", name, () -> {});
        heading.addView(toggle, new LinearLayout.LayoutParams(dp(56), dp(56)));
        card.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        TextView feedback = label("", 17, Color.WHITE);
        feedback.setPadding(0, dp(12), 0, 0);
        SeekBar dimmer = new SeekBar(activity);
        dimmer.setContentDescription(name + " brightness");
        dimmer.setThumb(null);
        dimmer.setPadding(0, 0, 0, 0);
        LayerDrawable track = new LayerDrawable(new android.graphics.drawable.Drawable[] {
                rounded(0xff594940, 20),
                new ClipDrawable(rounded(AMBER, 20), Gravity.LEFT, ClipDrawable.HORIZONTAL)
        });
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        dimmer.setProgressDrawable(track);
        dimmer.setProgress(on[0] ? Math.round(attributes.optInt("brightness", 255) * 100f / 255) : 0);
        renderState(name, on[0], toggle, stateLabel, bulb);
        feedback.setText(dimmer.getProgress() + "%");
        JSONArray modes = attributes.optJSONArray("supported_color_modes");
        boolean dimmable = attributes.has("brightness");
        if (modes != null) {
            for (int i = 0; i < modes.length(); i++) {
                String mode = modes.optString(i);
                dimmable |= !mode.isEmpty() && !"onoff".equals(mode) && !"unknown".equals(mode);
            }
        }
        if (dimmable) {
            card.addView(feedback);
            card.addView(dimmer, new LinearLayout.LayoutParams(-1, dp(44)));
        }
        toggle.setOnClickListener(view -> changeLamp(id, name, on, !on[0], -1, toggle, stateLabel, bulb, dimmer, feedback));
        dimmer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user) feedback.setText(value + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                int value = bar.getProgress();
                changeLamp(id, name, on, value > 0, value, toggle, stateLabel, bulb, dimmer, feedback);
            }
        });
    }

    private void renderState(String name, boolean on, TextView power, TextView state, TextView bulb) {
        state.setText(on ? "On" : "Off");
        state.setTextColor(on ? AMBER : MUTED);
        bulb.setAlpha(on ? 1f : 0.35f);
        power.setTextColor(on ? 0xff211914 : Color.WHITE);
        power.setBackground(rounded(on ? AMBER : 0xff564943, 28));
        power.setContentDescription((on ? "Turn off " : "Turn on ") + name);
    }

    private void changeLamp(String id, String name, boolean[] on, boolean enable, int brightness,
                            TextView toggle, TextView stateLabel, TextView bulb, SeekBar dimmer, TextView feedback) {
        toggle.setEnabled(false);
        dimmer.setEnabled(false);
        requests.execute(() -> {
            try {
                JSONObject data = new JSONObject().put("entity_id", id);
                if (enable && brightness > 0) data.put("brightness_pct", brightness);
                request("/api/services/light/" + (enable ? "turn_on" : "turn_off"), data);
                JSONObject state = (JSONObject) request("/api/states/" + id, null);
                activity.runOnUiThread(() -> {
                    on[0] = "on".equals(state.optString("state"));
                    renderState(name, on[0], toggle, stateLabel, bulb);
                    JSONObject values = state.optJSONObject("attributes");
                    int level = on[0] && values != null ? Math.round(values.optInt("brightness", 255) * 100f / 255) : 0;
                    dimmer.setProgress(level);
                    feedback.setText(level + "%");
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> feedback.setText("Could not confirm the change."));
            } finally {
                activity.runOnUiThread(() -> {
                    toggle.setEnabled(true);
                    dimmer.setEnabled(true);
                });
            }
        });
    }

    private Object request(String path, JSONObject data) throws Exception {
        URL url = new URL(preferences.getString("server", "") + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7000);
        connection.setInstanceFollowRedirects(false);
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
