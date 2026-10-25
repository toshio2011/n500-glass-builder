package com.n500.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Glass TV Remote for Android phones.
 *
 * Native Android application, not a WebView. Live View is a tab in this same
 * remote. No pretend screenshots: a frame appears only after authenticated
 * projector-side capture is running. The projector's TV-side control service
 * is a separate, required component and is never silently enabled.
 */
public final class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TvClient tv;
    private LinearLayout content;
    private TextView connection;
    private TextView liveStatus;
    private ImageView frameView;
    private int activeTab = 0;
    private volatile boolean viewActive;
    private volatile int frameGeneration;
    private boolean destroyed;

    interface Task { String run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 15, 28));
        getWindow().setNavigationBarColor(Color.rgb(9, 15, 28));
        buildUI();
    }

    private int dp(float size) { return Math.round(size * getResources().getDisplayMetrics().density); }

    private GradientDrawable bg(int color, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(17));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(size);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private TextView heading(String value) {
        TextView label = text(value, 22, Color.WHITE, true);
        label.setPadding(0, dp(14), 0, dp(9));
        return label;
    }

    private LinearLayout linear(boolean horizontal) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        return l;
    }

    private void pad(LinearLayout l, int vertical) {
        l.setPadding(dp(15), dp(vertical), dp(15), dp(vertical));
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setBackground(bg(Color.rgb(33, 47, 70), Color.rgb(60, 83, 112)));
        b.setPadding(dp(8), dp(7), dp(8), dp(7));
        return b;
    }

    private void add(LinearLayout parent, View view) {
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private void gap(LinearLayout parent, int height) {
        View spacer = new View(this);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private void addGridButton(LinearLayout row, String text, int weight, Runnable onTap) {
        Button b = button(text);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(62), weight);
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(b, p);
        b.setOnClickListener(v -> onTap.run());
    }

    private void buildUI() {
        LinearLayout base = linear(false);
        base.setBackgroundColor(Color.rgb(10, 17, 30));
        base.setPadding(dp(16), dp(12), dp(16), 0);
        setContentView(base);

        TextView title = text("GLASS TV   /   REMOTE", 20, Color.WHITE, true);
        add(base, title);
        connection = text("Not paired • Your v1.0.0 projector Home is untouched", 12, Color.rgb(180, 200, 220), false);
        connection.setPadding(0, dp(8), 0, dp(16));
        add(base, connection);

        LinearLayout tabs = linear(true);
        String[] labels = {"Remote", "Live View", "Setup"};
        for (int i = 0; i < labels.length; i++) {
            final int tab = i;
            Button b = button(labels[i]);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(49), 1);
            params.setMargins(dp(2), 0, dp(2), 0);
            tabs.addView(b, params);
            b.setOnClickListener(v -> showTab(tab));
        }
        add(base, tabs);
        gap(base, 8);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = linear(false);
        scroll.addView(content);
        base.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        showTab(0);
    }

    private void showTab(int index) {
        activeTab = index;
        content.removeAllViews();
        if (index == 0) buildRemote();
        else if (index == 1) buildLiveView();
        else buildSetup();
    }

    private void card(String headline, String desc) {
        LinearLayout card = linear(false);
        pad(card, 15);
        card.setBackground(bg(Color.rgb(20, 32, 49), Color.rgb(45, 64, 81)));
        add(card, text(headline, 16, Color.WHITE, true));
        TextView sub = text(desc, 13, Color.rgb(180, 195, 214), false);
        sub.setPadding(0, dp(5), 0, 0);
        add(card, sub);
        add(content, card);
        gap(content, 12);
    }

    private void buildRemote() {
        add(content, heading("Projector remote"));
        card("One remote, two apps",
                "Use your N500 physical remote as usual. This phone controller will talk to the paired projector service over your Wi-Fi.");
        LinearLayout r1 = linear(true);
        addGridButton(r1, "INPUT", 1, () -> send("INPUT"));
        addGridButton(r1, "HOME", 1, () -> send("HOME"));
        addGridButton(r1, "BACK", 1, () -> send("BACK"));
        add(content, r1);

        LinearLayout up = linear(true);
        addGridButton(up, " ", 1, () -> {});
        addGridButton(up, "▲", 1, () -> send("UP"));
        addGridButton(up, " ", 1, () -> {});
        add(content, up);
        LinearLayout middle = linear(true);
        addGridButton(middle, "◀", 1, () -> send("LEFT"));
        addGridButton(middle, "OK", 1, () -> send("OK"));
        addGridButton(middle, "▶", 1, () -> send("RIGHT"));
        add(content, middle);
        LinearLayout down = linear(true);
        addGridButton(down, " ", 1, () -> {});
        addGridButton(down, "▼", 1, () -> send("DOWN"));
        addGridButton(down, " ", 1, () -> {});
        add(content, down);

        gap(content, 10);
        LinearLayout volume = linear(true);
        addGridButton(volume, "VOL −", 1, () -> send("VOL_DOWN"));
        addGridButton(volume, "MUTE", 1, () -> send("MUTE"));
        addGridButton(volume, "VOL +", 1, () -> send("VOL_UP"));
        add(content, volume);

        gap(content, 16);
        Button pair = button("Connect / Pair N500");
        add(content, pair);
        pair.setOnClickListener(v -> showPairing());
        gap(content, 15);
        card("Remote availability", "The phone APK is built separately from the TV controller. Buttons require a paired N500 Preview with its controller endpoint running. Home/Back and volume may require device-specific integration outside the launcher.");
    }

    private void buildLiveView() {
        add(content, heading("Live View  ·  Watch projector"));
        card("See the projector on your phone",
                "This tab is part of Glass TV Remote. A real picture is only displayed if N500 has started an approved screen-capture session. No fake images or DRM bypass.");
        frameView = new ImageView(this);
        frameView.setBackground(bg(Color.rgb(3, 8, 14), Color.rgb(50, 80, 106)));
        frameView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(frameView, new LinearLayout.LayoutParams(-1, dp(215)));
        liveStatus = text("Live View is OFF", 14, Color.rgb(183, 206, 228), false);
        liveStatus.setPadding(0, dp(13), 0, dp(13));
        add(content, liveStatus);

        LinearLayout buttons = linear(true);
        addGridButton(buttons, "Start Live View", 1, this::startLive);
        addGridButton(buttons, "STOP", 1, this::stopLive);
        add(content, buttons);
        gap(content, 10);
        add(content, text("Capture requires projector-side approval each session. Protected streaming content may show black; HDMI may not be capturable. Initial goal: silent, low-frame-rate local preview.", 13, Color.rgb(188, 207, 223), false));
        gap(content, 12);
        LinearLayout navigation = linear(true);
        addGridButton(navigation, "◀", 1, () -> send("LEFT"));
        addGridButton(navigation, "OK", 1, () -> send("OK"));
        addGridButton(navigation, "▶", 1, () -> send("RIGHT"));
        add(content, navigation);
        LinearLayout other = linear(true);
        addGridButton(other, "Back", 1, () -> send("BACK"));
        addGridButton(other, "Home", 1, () -> send("HOME"));
        add(content, other);
    }

    private void buildSetup() {
        add(content, heading("Connect to your N500"));
        card("Local Wi-Fi pairing",
                "Use the TV-side Phone Link menu to display a one-time six-digit code. Your phone and projector must use the same Wi-Fi.");
        Button pair = button("Enter projector IP and pairing code");
        add(content, pair);
        pair.setOnClickListener(v -> showPairing());
        gap(content, 12);
        Button check = button("Check connection");
        add(content, check);
        check.setOnClickListener(v -> task("Checking projector…", () -> {
            if (tv == null || !tv.isPaired()) throw new IllegalStateException("Pair first");
            JSONObject status = tv.status();
            return "Connected: " + status.optString("name", "Glass TV") + " • " + tv.address();
        }));
        gap(content, 12);
        Button disconnect = button("Forget pairing");
        add(content, disconnect);
        disconnect.setOnClickListener(v -> {
            stopLive();
            if (tv != null) tv.clearPairing();
            connection.setText("Not paired");
            Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
        });
        gap(content, 16);
        card("Phone controller developer preview",
                "The current projector v1.0.0 APK has NO remote-control API. Pairing and streaming require the next paired TV preview build. The remote is not connected through ADB.");
        card("Privacy", "Live View must never start silently. This MVP uses unencrypted private-LAN HTTP for development and must not be exposed to public Wi-Fi or forwarded to the Internet. Encrypted transport is required before general release.");
    }

    private void showPairing() {
        LinearLayout fields = linear(false);
        pad(fields, 12);
        EditText ip = new EditText(this);
        ip.setText(getPreferences(MODE_PRIVATE).getString("ip", "192.168.100.243:8765"));
        ip.setSingleLine(true);
        ip.setHint("Projector IP:port");
        ip.setTextColor(Color.WHITE);
        ip.setHintTextColor(0xFFAAAAAA);
        add(fields, ip);
        EditText pin = new EditText(this);
        pin.setHint("6-digit code from projector");
        pin.setInputType(2); // TYPE_CLASS_NUMBER
        pin.setSingleLine(true);
        pin.setTextColor(Color.WHITE);
        pin.setHintTextColor(0xFFAAAAAA);
        add(fields, pin);
        new AlertDialog.Builder(this)
                .setTitle("Pair with N500")
                .setView(fields)
                .setPositiveButton("Pair", (dialog, which) -> {
                    String host = ip.getText().toString().trim();
                    String code = pin.getText().toString().trim();
                    task("Pairing…", () -> {
                        TvClient candidate = new TvClient(host);
                        candidate.pair(code);
                        tv = candidate;
                        getPreferences(MODE_PRIVATE).edit().putString("ip", host).apply();
                        return "Paired to " + candidate.address();
                    });
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void task(String progress, Task work) {
        connection.setText(progress);
        io.execute(() -> {
            String message;
            try { message = work.run(); }
            catch (Exception e) { message = "Not connected: " + e.getMessage(); }
            final String result = message;
            ui.post(() -> {
                if (destroyed) return;
                connection.setText(result);
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void send(String key) {
        task("Sending " + key + "…", () -> {
            if (tv == null || !tv.isPaired()) throw new IllegalStateException("Pair your N500 first");
            JSONObject result = tv.key(key);
            return result.optString("message", "Sent " + key);
        });
    }

    private void startLive() {
        if (viewActive) return;
        if (tv == null || !tv.isPaired()) {
            Toast.makeText(this, "Pair your N500 first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (liveStatus != null) liveStatus.setText("Requesting projector permission…");
        task("Requesting Live View…", () -> {
            JSONObject result = tv.startView();
            viewActive = true;
            final int generation = ++frameGeneration;
            ui.post(() -> {
                if (liveStatus != null) liveStatus.setText(
                        result.optString("message", "Approve screen sharing on the projector."));
                poll(generation);
            });
            return "Live View requested • approve on the projector";
        });
    }

    private void poll(int generation) {
        if (!viewActive || generation != frameGeneration || destroyed) return;
        io.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (tv == null) throw new IllegalStateException("Not paired");
                byte[] bytes = tv.frame();
                if (bytes.length > 0) bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception e) {
                final String failure = e.getMessage();
                ui.post(() -> {
                    if (liveStatus != null && generation == frameGeneration) {
                        liveStatus.setText("No frame yet: " + failure);
                    }
                });
            }
            final Bitmap ready = bitmap;
            ui.post(() -> {
                if (!viewActive || generation != frameGeneration || destroyed) {
                    if (ready != null) ready.recycle();
                    return;
                }
                if (ready != null && frameView != null) {
                    frameView.setImageBitmap(ready);
                    if (liveStatus != null) liveStatus.setText("LIVE • N500 screen capture");
                }
                ui.postDelayed(() -> poll(generation), 1200);
            });
        });
    }

    private void stopLive() {
        viewActive = false;
        frameGeneration++;
        if (liveStatus != null) liveStatus.setText("Live View is OFF");
        if (frameView != null) frameView.setImageDrawable(null);
        if (tv != null && tv.isPaired()) {
            io.execute(() -> {
                try { tv.stopView(); } catch (Exception ignored) {}
            });
        }
    }

    @Override protected void onStop() {
        stopLive();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopLive();
        io.shutdownNow();
        super.onDestroy();
    }
}
