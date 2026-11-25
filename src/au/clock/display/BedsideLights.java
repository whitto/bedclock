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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final String[] DEFAULT_NAMES = {"Lamp Craig", "Lamp Kristen"};
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
        TextView settings = action("⚙", "Devices to control", () -> {
            dialog.dismiss();
            new LightSettings().show();
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
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        panel.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(panel);
        screen.addView(scroll, cards);

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
            panel.addView(label("Open the gear to connect Home Assistant.", 16, MUTED));
            return;
        }
        lamps.refresh();
    }

    private String lightName(JSONObject state) throws Exception {
        String id = state.getString("entity_id");
        return state.getJSONObject("attributes").optString("friendly_name", id);
    }

    private static final String ALL_OFF = "action.all_lights_off";

    private int controlCount() {
        return preferences.getInt("controlCount", DEFAULT_NAMES.length);
    }

    private String savedName(int index) {
        return preferences.getString("lightName" + index, index < DEFAULT_NAMES.length ? DEFAULT_NAMES[index] : "Control");
    }

    private static String kind(String id) {
        if (ALL_OFF.equals(id)) return "Action · All Home Assistant lights";
        if (id != null && id.startsWith("input_boolean.")) return "Toggle helper";
        if (id != null && id.startsWith("switch.")) return "Switch";
        return "Light";
    }

    private final class LightSettings {

        final List<LampState> lights = new ArrayList<>();
        final List<String> selected = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<String> original = new ArrayList<>();
        final TextView message = label("", 15, MUTED);
        LinearLayout rows;
        Dialog page;
        Button save, add;
        boolean loaded;

        Button control(String name, boolean primary) {
            Button button = new Button(activity);
            button.setAllCaps(false);
            button.setText(name);
            button.setTextSize(15);
            button.setTextColor(primary ? 0xff202122 : Color.WHITE);
            button.setBackground(rounded(primary ? 0xffe2e3e4 : 0xff444648, 8));
            button.setPadding(dp(16), 0, dp(16), 0);
            button.setMinHeight(dp(44));
            return button;
        }

        LinearLayout screen(String name, Button button) {
            LinearLayout form = column();
            form.setPadding(dp(22), dp(14), dp(22), dp(10));
            form.setBackgroundColor(0xff191a1b);
            form.setFocusableInTouchMode(true);
            LinearLayout header = new LinearLayout(activity);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = label(name, 23, Color.WHITE);
                title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTypeface(null, Typeface.BOLD);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            header.addView(button, new LinearLayout.LayoutParams(-2, dp(44)));
            form.addView(header);
            return form;
        }

        Dialog fullPage(LinearLayout form) {
            Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
            dialog.setContentView(form);
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            dialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            return dialog;
        }

        void renderRows() {
            rows.removeAllViews();
            for (int i = 0; i < selected.size(); i++) {
                final int slot = i;
                LinearLayout row = new LinearLayout(activity);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(6), dp(12), dp(6));
                row.setBackground(rounded(CARD, 10));
                LinearLayout caption = column();
                caption.addView(label(kind(selected.get(i)), 14, MUTED));
                TextView name = label(names.get(i), 18, Color.WHITE);
                name.setSingleLine();
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                caption.addView(name);
                row.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
                Button change = control("Change", false);
                change.setEnabled(loaded);
                change.setOnClickListener(v -> choose(slot));
                row.addView(change);
                Button remove = control("Remove", false);
                remove.setEnabled(loaded);
                remove.setOnClickListener(v -> {
                    selected.remove(slot);
                    names.remove(slot);
                    renderRows();
                });
                row.addView(remove);
                LinearLayout.LayoutParams card = new LinearLayout.LayoutParams(-1, -2);
                card.topMargin = dp(7);
                rows.addView(row, card);
            }
            boolean changed = !selected.equals(original);
            save.setEnabled(changed);
            save.setAlpha(changed ? 1f : .55f);
        }

        void show() {
            for (int i = 0; i < controlCount(); i++) {
                selected.add(preferences.getString("lightId" + i, null));
                names.add(savedName(i));
            }
            original.addAll(selected);
            save = control("Save", true);
            LinearLayout form = screen("Devices to control", save);
            ScrollView scroll = new ScrollView(activity);
            rows = column();
            scroll.addView(rows);
            form.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            form.addView(message);
            page = fullPage(form);
            LinearLayout footer = new LinearLayout(activity);
            add = control("Add control", false);
            add.setEnabled(false);
            add.setOnClickListener(v -> choose(selected.size()));
            footer.addView(add);
            Button connection = control("Connection", false);
            Button cancel = control("Cancel", false);
            connection.setBackgroundColor(Color.TRANSPARENT);
            cancel.setBackgroundColor(Color.TRANSPARENT);
            footer.addView(connection);
            footer.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
            footer.addView(cancel);
            form.addView(footer);
            renderRows();
            cancel.setOnClickListener(v -> page.dismiss());
            connection.setOnClickListener(v -> {
                page.setOnDismissListener(null);
                page.dismiss();
                connection();
            });
            save.setOnClickListener(v -> {
                SharedPreferences.Editor edit = preferences.edit();
                for (int i = selected.size(); i < controlCount(); i++)
                    edit.remove("lightId" + i).remove("lightName" + i);
                for (int i = 0; i < selected.size(); i++) {
                    edit.putString("lightId" + i, selected.get(i)).putString("lightName" + i, names.get(i));
                }
                edit.putInt("controlCount", selected.size()).apply();
                page.dismiss();
            });
            page.setOnDismissListener(d -> { if (!activity.isDestroyed() && !activity.isFinishing()) open(); });
            page.show();
            page.getWindow().setLayout(-1, -1);
            if (preferences.getString("token", "").isEmpty()) {
                message.setText("Open Connection to link Home Assistant.");
                return;
            }
            message.setText("Loading lights…");
            reads.execute(() -> {
                List<LampState> found = new ArrayList<>();
                try {
                    JSONArray all = (JSONArray) request("/api/states", null);
                    for (int i = 0; i < all.length(); i++) {
                        JSONObject state = all.getJSONObject(i);
                        String id = state.optString("entity_id");
                        if (id.startsWith("light.") || id.startsWith("input_boolean.") || id.startsWith("switch."))
                            found.add(decodeLamp(state));
                    }
                    found.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                } catch (Exception e) {
                    ui.post(() -> {
                        if (page.isShowing()) message.setText("Couldn’t load lights. Check Connection and try again.");
                    });
                    return;
                }
                ui.post(() -> {
                    if (!page.isShowing()) return;
                    lights.add(new LampState(ALL_OFF, "Turn all lights off", "", -1, false));
                    lights.addAll(found);
                    message.setText("");
                    message.setVisibility(View.GONE);
                    for (int i = 0; i < selected.size(); i++) {
                        String id = selected.get(i);
                        if (id == null) {
                            List<String> matches = new ArrayList<>();
                            for (LampState light : found) {
                                if (light.id.startsWith("light.") && names.get(i).equalsIgnoreCase(light.name))
                                    matches.add(light.id);
                            }
                            if (matches.size() == 1 && !selected.contains(matches.get(0))) selected.set(i, matches.get(0));
                        } else {
                            for (LampState light : found) if (light.id.equals(id)) names.set(i, light.name);
                        }
                    }
                    original.clear();
                    original.addAll(selected);
                    loaded = true;
                    add.setEnabled(true);
                    renderRows();
                });
            });
        }

        void choose(int slot) {
            Button back = control("Back", true);
            LinearLayout form = screen(slot == selected.size() ? "Add control" : "Change control", back);
            EditText search = new EditText(activity);
            search.setSingleLine();
            search.setHint("Search devices or actions");
            form.setFocusableInTouchMode(true);
            search.setBackground(rounded(0xff333537, 8));
            search.setPadding(dp(14), 0, dp(14), 0);
            LinearLayout.LayoutParams field = new LinearLayout.LayoutParams(-1, dp(44));
            field.setMargins(0, dp(12), 0, dp(12));
            form.addView(search, field);
            ListView list = new ListView(activity);
            form.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            TextView empty = label("No matching controls", 16, MUTED);
            form.addView(empty);
            List<LampState> filtered = new ArrayList<>();
            list.setDivider(new android.graphics.drawable.ColorDrawable(0xff191a1b));
            list.setDividerHeight(dp(8));
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1) {
                @Override public boolean areAllItemsEnabled() { return false; }
                @Override public boolean isEnabled(int position) {
                    int existing = selected.indexOf(filtered.get(position).id); return existing < 0 || existing == slot;
                }
                @Override public View getView(int position, View row, android.view.ViewGroup parent) {
                    TextView name = (TextView) super.getView(position, row, parent);
                    name.setBackground(rounded(0xff444648, 8));
                    name.setTextColor(isEnabled(position) ? Color.WHITE : MUTED);
                    name.setTextSize(15);
                    return name;
                }
            };
            list.setAdapter(adapter);
            Runnable filter = () -> {
                String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
                filtered.clear();
                adapter.clear();
                for (LampState light : lights) {
                    String description = light.name + " · " + kind(light.id);
                    if (!(description + " " + light.id).toLowerCase(Locale.ROOT).contains(query)) continue;
                    filtered.add(light);
                    int existing = selected.indexOf(light.id);
                    adapter.add(description + (existing < 0 ? "" : existing == slot ? "  ✓" : " · Added"));
                }
                empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
            };
            search.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
                public void afterTextChanged(Editable s) {}
            });
            filter.run();
            Dialog picker = fullPage(form);
            back.setOnClickListener(v -> picker.dismiss());
            list.setOnItemClickListener((parent, view, position, id) -> {
                LampState light = filtered.get(position);
                int existing = selected.indexOf(light.id);
                if (existing >= 0 && existing != slot) return;
                if (slot == selected.size()) {
                    selected.add(light.id);
                    names.add(light.name);
                } else {
                    selected.set(slot, light.id);
                    names.set(slot, light.name);
                }
                renderRows();
                picker.dismiss();
            });
            picker.show();
            picker.getWindow().setLayout(-1, -1);
            picker.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
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
        private final Lamp[] lamps = new Lamp[controlCount()];
        private final Runnable refreshTask = this::refresh;

        LampPanel(Dialog dialog, LinearLayout panel) {
            this.dialog = dialog;
            if (lamps.length == 0) panel.addView(label("Open the gear to add controls.", 18, MUTED));
            LinearLayout row = null;
            for (int index = 0; index < lamps.length; index++) {
                if (index % 3 == 0) {
                    row = new LinearLayout(activity);
                    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1,
                            lamps.length <= 3 ? -1 : dp(180));
                    if (index > 0) space.topMargin = dp(10);
                    panel.addView(row, space);
                }
                lamps[index] = new Lamp(row, index);
            }
        }

        void refresh() {
            if (lamps.length == 0 || !dialog.isShowing() || reads.isShutdown()) return;
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
                        if (ALL_OFF.equals(ids[index])) continue;
                        if (ids[index] != null) {
                            states[index] = readLamp(ids[index]);
                        } else {
                            if (!discoveryTried) {
                                discoveryTried = true;
                                all = (JSONArray) request("/api/states", null);
                            }
                            if (all != null) {
                                LampState candidate = decodeLamp(findLamp(all, savedName(index)));
                                if (!java.util.Arrays.asList(ids).contains(candidate.id)) states[index] = candidate;
                            }
                        }
                    } catch (Exception error) {
                        android.util.Log.d("BedsideClock", "Could not refresh control " + index);
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
            final int slot;
            final TextView title;
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

            Lamp(LinearLayout panel, int slot) {
                this.slot = slot;
                id = preferences.getString("lightId" + slot, null);
                String name = savedName(slot);
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
                if (lamps.length < 3 && (id == null || id.startsWith("light."))) heading.addView(bulb, new LinearLayout.LayoutParams(dp(42), -2));
                LinearLayout labels = column();
                title = label(name, lamps.length >= 3 ? 18 : 23, Color.WHITE);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                title.setTypeface(null, Typeface.BOLD);
                labels.addView(title);
                status = label("", 17, MUTED);
                labels.addView(status);
                heading.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                power = action("", name, () -> {
                    if (ALL_OFF.equals(id)) sendAllOff();
                    else if (state != null && state.available) change(!state.on, -1);
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
                if (!ALL_OFF.equals(id)) status.setText("Loading…");
            }

            void apply(LampState latest) {
                if (ALL_OFF.equals(id)) {
                    if (status.length() == 0) status.setText("Whole house");
                    dimmer.setVisibility(View.GONE);
                    feedback.setVisibility(View.GONE);
                    power.setContentDescription("Turn off all Home Assistant lights");
                    return;
                }
                state = latest;

                if (latest != null) {
                    if (!latest.id.equals(id) || !latest.name.contentEquals(title.getText()) || !preferences.contains("lightId" + slot)) {
                        preferences.edit().putString("lightId" + slot, latest.id)
                                .putString("lightName" + slot, latest.name).apply();
                    }
                    id = latest.id;
                    title.setText(latest.name);
                    dimmer.setContentDescription(latest.name + " brightness");
                }
                String name = title.getText().toString();
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

            void sendAllOff() {
                if (busy || requests.isShutdown()) return;
                busy = true;
                power.setEnabled(false);
                status.setText("Sending…");
                requests.execute(() -> {
                    boolean sent = false;
                    try {
                        request("/api/services/light/turn_off", new JSONObject().put("entity_id", "all"));
                        sent = true;
                    } catch (Exception ignored) {}
                    String response = sent ? "Off command sent" : "Couldn’t send command";
                    ui.post(() -> {
                        if (!dialog.isShowing()) return;
                        busy = false;
                        power.setEnabled(true);
                        status.setText(response);
                    });
                });
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
                        String domain = entityId.substring(0, entityId.indexOf('.'));
                        request("/api/services/" + domain + "/" + (turnOn ? "turn_on" : "turn_off"), data);
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
        return new LampState(entity.getString("entity_id"), lightName(entity), entity.getString("state"),
                attributes.optInt("brightness", -1), entity.getString("entity_id").startsWith("light.") && dimmable);
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
