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
import org.json.JSONArray;
import android.widget.FrameLayout;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

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
    private final Button[] navButtons = new Button[4];
    private static final int INK = 0xFF0B1622;
    private static final int PANEL = 0xFF182B3C;
    private static final int MINT = 0xFF9AE9D2;
    private static final int MUTED = 0xFFABC0CD;
    private volatile boolean viewActive;
    private volatile int frameGeneration;
    private boolean destroyed;
    private volatile boolean reconnecting;

    interface Task { String run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 15, 28));
        getWindow().setNavigationBarColor(Color.rgb(9, 15, 28));
        buildUI();
    }

    @Override protected void onResume() {
        super.onResume();
        reconnectRememberedPhone();
    }

    private int dp(float size) { return Math.round(size * getResources().getDisplayMetrics().density); }

    private GradientDrawable bg(int color, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(21));
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
        TextView label = text(value, 23, Color.WHITE, true);
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
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(bg(0xFF263B4E, 0xFF3C5B6E));
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(55), weight);
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(b, p);
        b.setOnClickListener(v -> onTap.run());
    }

    private void buildUI() {
        LinearLayout base = linear(false);
        android.graphics.drawable.GradientDrawable backdrop = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF091623, 0xFF101F2E, 0xFF07131B});
        base.setBackground(backdrop);
        base.setPadding(dp(20), dp(16), dp(20), dp(8));
        setContentView(base);

        LinearLayout header = linear(true);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView emblem = text("◈", 31, MINT, true);
        emblem.setGravity(Gravity.CENTER);
        emblem.setBackground(bg(0xFF203D49, 0xFF527B79));
        header.addView(emblem, new LinearLayout.LayoutParams(dp(55), dp(55)));

        LinearLayout titles = linear(false);
        titles.setPadding(dp(13), 0, 0, 0);
        add(titles, text("GLASS  /  N500", 19, Color.WHITE, true));
        TextView subtitle = text("Your projector, within reach.", 12, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, 0);
        add(titles, subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        add(base, header);
        gap(base, 14);

        LinearLayout state = linear(true);
        state.setGravity(Gravity.CENTER_VERTICAL);
        state.setPadding(dp(14), dp(12), dp(14), dp(12));
        state.setBackground(bg(0xA21D3342, 0xFF3B5C65));
        TextView dot = text("●", 14, MINT, true);
        state.addView(dot, new LinearLayout.LayoutParams(dp(22), -2));
        connection = text("Not paired • Connect to your N500", 12, 0xFFDFECEF, false);
        state.addView(connection, new LinearLayout.LayoutParams(0, -2, 1));
        add(base, state);

        gap(base, 10);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        content = linear(false);
        content.setPadding(0, dp(4), 0, dp(12));
        scroll.addView(content);
        base.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout navbar = linear(true);
        navbar.setPadding(dp(4), dp(8), dp(4), dp(5));
        navbar.setBackground(bg(0xF0192A39, 0xFF395467));
        String[] labels = {"◉  Remote", "▣  Live", "▦  Apps", "⚙  Setup"};
        for (int i=0;i<labels.length;i++) {
            final int tab = i;
            Button b = button(labels[i]);
            b.setTextSize(11);
            b.setPadding(0, 0, 0, 0);
            navButtons[i] = b;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1);
            p.setMargins(dp(2),0,dp(2),0);
            navbar.addView(b, p);
            b.setOnClickListener(v -> showTab(tab));
        }
        add(base, navbar);
        showTab(0);
    }
    private void showTab(int index) {
        if (activeTab == 1 && index != 1) stopLive();
        activeTab = index;
        content.removeAllViews();
        for (int i=0;i<navButtons.length;i++) {
            if(navButtons[i] != null) {
                boolean on=i==index;
                navButtons[i].setTextColor(on ? INK : 0xFFD3E0E7);
                navButtons[i].setBackground(bg(on ? MINT : 0x00203246, on ? MINT : 0));
            }
        }
        if (index == 0) buildRemote();
        else if (index == 1) buildLiveView();
        else if (index == 2) buildApps();
        else buildSetup();
    }
    private void card(String headline, String desc) {
        LinearLayout card = linear(false);
        pad(card, 15);
        card.setBackground(bg(0xD5203444, 0xFF375165));
        add(card, text(headline, 15, Color.WHITE, true));
        TextView sub = text(desc, 12, MUTED, false);
        sub.setLineSpacing(dp(3), 1);
        sub.setPadding(0, dp(5), 0, 0);
        add(card, sub);
        add(content, card);
        gap(content, 12);
    }
    private void buildRemote() {
        add(content, heading("The remote."));
        TextView sub = text("A little control, a lot of possibilities.", 13, MUTED, false);
        add(content, sub);
        gap(content, 18);

        LinearLayout controls = linear(true);
        addGridButton(controls, "⌂  Home", 1, () -> send("HOME"));
        addGridButton(controls, "⤺  Back", 1, () -> send("BACK"));
        addGridButton(controls, "▣  Input", 1, () -> send("INPUT"));
        add(content, controls);
        gap(content, 17);

        LinearLayout dpadHolder = linear(false);
        dpadHolder.setGravity(Gravity.CENTER);
        dpadHolder.setPadding(dp(11), dp(16), dp(11), dp(16));
        dpadHolder.setBackground(bg(0xC01B2E3C, 0xFF355264));
        GlassDpad dpad = new GlassDpad(this, this::send);
        dpadHolder.addView(dpad, new LinearLayout.LayoutParams(
                dp(265), dp(265)));
        add(content, dpadHolder);
        gap(content, 18);

        TextView volumeTitle = text("VOLUME", 11, 0xFF91B3BD, true);
        volumeTitle.setLetterSpacing(.18f);
        add(content, volumeTitle);
        gap(content, 7);
        LinearLayout volume = linear(true);
        addGridButton(volume, "−", 1, () -> send("VOL_DOWN"));
        addGridButton(volume, "◌  Mute", 2, () -> send("MUTE"));
        addGridButton(volume, "+", 1, () -> send("VOL_UP"));
        add(content, volume);
        gap(content, 16);
        card("Remote status", "Navigation currently controls Glass TV Preview while it is in front. Other apps and HDMI still use your physical remote.");
    }
    private void buildLiveView() {
        add(content, heading("Live on your wall."));
        add(content, text("An actual view of your projector screen, right here.", 13, MUTED, false));
        gap(content, 16);
        FrameLayout viewport = new FrameLayout(this);
        viewport.setBackground(bg(0xFF02090E, 0xFF3E5B68));
        frameView = new ImageView(this);
        frameView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewport.addView(frameView, new FrameLayout.LayoutParams(-1,-1));
        TextView marker = text("◉  PROJECTOR LIVE VIEW", 11, MINT, true);
        marker.setPadding(dp(14), dp(10), dp(14), dp(10));
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-2,-2,Gravity.TOP | Gravity.LEFT);
        viewport.addView(marker,mp);
        TextView idle = text("SCREEN PREVIEW", 14, 0xFF68828F, true);
        idle.setGravity(Gravity.CENTER);
        viewport.addView(idle, new FrameLayout.LayoutParams(-1,-1));
        // A real frame is always drawn in ImageView; placeholder must disappear
        // once capture returns content. It must not pretend to be a screenshot.
        frameView.setTag(idle);
        addFrame(content, viewport, dp(228));
        gap(content, 11);
        liveStatus = text("●  OFFLINE  •  Start sharing to see your N500", 12, MUTED, false);
        add(content, liveStatus);
        gap(content, 13);

        LinearLayout buttons = linear(true);
        addGridButton(buttons, "◉  Start Live View", 2, this::startLive);
        addGridButton(buttons, "■  Stop", 1, this::stopLive);
        add(content, buttons);
        gap(content, 18);
        add(content, text("QUICK CONTROLS", 11, MUTED, true));
        gap(content, 7);
        LinearLayout navigation = linear(true);
        addGridButton(navigation, "◀", 1, () -> send("LEFT"));
        addGridButton(navigation, "OK", 1, () -> send("OK"));
        addGridButton(navigation, "▶", 1, () -> send("RIGHT"));
        add(content, navigation);
        LinearLayout other = linear(true);
        addGridButton(other, "↩  Back", 1, () -> send("BACK"));
        addGridButton(other, "⌂  Home", 1, () -> send("HOME"));
        add(content, other);
        gap(content, 12);
        card("Private by design", "Approve capture on the projector each time. Audio isn't available yet. Protected streaming video and HDMI may not be capturable.");
    }

    private void addFrame(LinearLayout parent, View view, int h) {
        parent.addView(view,new LinearLayout.LayoutParams(-1,h));
    }
    private void buildSetup() {
        add(content, heading("Make the connection."));
        add(content,text("One screen. One phone. Same home Wi-Fi.",13,MUTED,false));
        gap(content,16);
        card("01   Open Phone Link", "On the N500 Glass TV Preview, open Settings → Phone Link (Experimental).");
        card("02   Pair securely", "Enter the projector IP and the six-digit code displayed on the projector. The code expires after two minutes.");

        Button pair = button("✦   Pair another projector");
        add(content,pair);
        pair.setOnClickListener(v -> showPairing());
        gap(content,10);
        Button check = button("◎   Check projector connection");
        add(content,check);
        check.setOnClickListener(v -> task("Checking projector…", () -> {
            if (tv == null || !tv.isPaired()) throw new IllegalStateException("Pair first");
            JSONObject status = tv.status();
            return "Connected: " + status.optString("name", "Glass TV") + " • " + tv.address();
        }));
        gap(content,10);
        Button disconnect = button("⤫   Forget pairing");
        add(content,disconnect);
        disconnect.setOnClickListener(v -> {
            stopLive();
            if(tv != null) tv.clearPairing();
            tv = null;
            N500PairingStore.clear(getApplicationContext());
            connection.setText("Pairing forgotten • your N500 can revoke the phone from Phone Link");
            Toast.makeText(this,"Disconnected",Toast.LENGTH_SHORT).show();
        });
        gap(content,19);
        card("Privacy and preview limits", "Live View needs projector approval. Current development connection uses private-LAN HTTP without encryption. Only use it on trusted Wi-Fi; don't expose port 8765 to the internet.");
    }

    private void buildApps() {
        add(content,heading("Everything, in reach."));
        add(content,text("Launch apps actually installed on your N500.",13,MUTED,false));
        gap(content,15);
        LinearLayout list=linear(false);
        card("Discover your projector", "App list comes from the paired N500. Continue Watching and Recommendations only appear on the TV when real source data is accessible.");
        add(content,list);
        if(tv==null || !tv.isPaired()) {
            add(list,text("Pair with your N500 in Setup to load its apps.",14,MUTED,false));
            return;
        }
        add(list,text("Loading installed apps…",14,MUTED,false));
        io.execute(() -> {
            JSONObject response=null;
            String err=null;
            try {response=tv.apps();} catch(Exception e) {err=e.getMessage();}
            final JSONObject found=response;
            final String failure=err;
            ui.post(() -> {
                if(destroyed || activeTab!=2) return;
                list.removeAllViews();
                if(failure!=null) {
                    add(list,text("Couldn't load installed apps: "+failure,13,MUTED,false));
                    return;
                }
                JSONArray apps=found.optJSONArray("apps");
                if(apps==null || apps.length()==0) {
                    add(list,text("No launchable apps were returned by the projector.",13,MUTED,false));
                    return;
                }
                for(int i=0;i<apps.length();i+=2) {
                    LinearLayout line=linear(true);
                    for(int k=i;k<Math.min(i+2,apps.length());k++) {
                        JSONObject app=apps.optJSONObject(k);
                        if(app==null)continue;
                        String label=app.optString("label","App");
                        String pkg=app.optString("package","");
                        Button b=button("▣  "+label);
                        b.setTextSize(12);
                        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(70),1);
                        lp.setMargins(dp(3),dp(3),dp(3),dp(3));
                        line.addView(b,lp);
                        b.setOnClickListener(v-> task("Opening "+label+"…",()->{
                            if(tv==null)throw new IllegalStateException("Not paired");
                            return tv.launch(pkg).optString("message","Opening "+label);
                        }));
                    }
                    add(list,line);
                }
            });
        });
    }
    /**
     * Reconnect automatically when phone Remote is opened / brought forward.
     * No repeated retries in background; keep saved credentials on Wi-Fi loss.
     * Invalid server token triggers explicit re-pair, rather than trying stale PIN.
     */
    private void reconnectRememberedPhone() {
        if (destroyed || reconnecting) return;
        String savedAddress = N500PairingStore.readAddress(getApplicationContext());
        if (savedAddress.isEmpty()) return;

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities network = cm == null ? null :
                cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (network == null || !(network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || network.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            connection.setText("N500 remembered • connect to your home Wi-Fi");
            return;
        }
        reconnecting = true;
        connection.setText("Reconnecting to remembered N500…");
        io.execute(() -> {
            String message;
            try {
                String savedToken=N500PairingStore.readToken(getApplicationContext());
                if (savedToken == null) throw new IllegalStateException(
                        "Remembered key unavailable. Pair once more.");
                TvClient restored=TvClient.restore(savedAddress,savedToken);
                JSONObject status=restored.status();
                tv=restored;
                message="Connected automatically • " + status.optString("name","N500");
            } catch(Exception e) {
                String error=e.getMessage()==null ? "Projector offline" : e.getMessage();
                if(error.contains("HTTP 401")) {
                    N500PairingStore.clear(getApplicationContext());
                    tv=null;
                    message="Previous pairing expired • open N500 Phone Link to pair again";
                } else {
                    // Projector Preview might not be open or Wi-Fi might be sleeping.
                    // Never erase a valid saved token merely because of network loss.
                    message="N500 remembered • offline for now (open Preview to reconnect)";
                }
            }
            final String result=message;
            ui.post(() -> {
                reconnecting=false;
                if(destroyed)return;
                connection.setText(result);
                if(activeTab==2 && tv!=null && tv.isPaired()) showTab(2);
            });
        });
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
                        // Store a token only inside encrypted AndroidKeyStore data.
                        // Keeping a projector IP in plaintext is fine, not the token.
                        N500PairingStore.save(getApplicationContext(),
                                candidate.address(), candidate.pairingToken());
                        tv = candidate;
                        getPreferences(MODE_PRIVATE).edit().putString("ip", host).apply();
                        return "Paired and remembered • " + candidate.address();
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
                    if (frameView.getTag() instanceof View) ((View) frameView.getTag()).setVisibility(View.GONE);
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
