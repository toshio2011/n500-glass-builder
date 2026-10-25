package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * N500 content area (experimental non-HOME preview).
 *
 * Discover actual installed streaming apps, actual app launch history, and
 * genuine Android TV Watch Next / PreviewProgram rows IF the provider is readable.
 * Never fabricate Netflix/Disney histories or "continue watching" titles.
 */
final class N500HomeSections {
    private static final String PREFS = "n500_content_hub";
    private static final String KEY_COLLAPSE = "collapse_on_browse";
    private static final String KEY_APPS = "streaming_apps";
    private static final String KEY_WATCH = "watch_next";
    private static final String KEY_RECOMMEND = "recommendations";
    private static final String KEY_RECENT = "recent_apps";
    private static final int WHITE = Color.WHITE;
    private static final int SUBTLE = Color.rgb(188, 204, 222);
    private final MainActivity activity;
    private final LinearLayout host;
    private final Handler handler;
    private final View header;
    private final LinearLayout shelf;
    private final SharedPreferences prefs;
    private boolean collapsed;
    private boolean lastWatchReadable;
    private int itemCount;
    private final List<Intent> contentIntents = new ArrayList<>();
    private boolean destroyed;

    private N500HomeSections(MainActivity activity, Handler handler, LinearLayout mainContent) {
        this.activity = activity;
        this.handler = handler;
        this.host = mainContent;
        prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        header = mainContent.findViewById(R.id.n500Header);
        shelf = new LinearLayout(activity);
        shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.setFocusable(false);
        host.addView(shelf, 1, new LinearLayout.LayoutParams(-1, -2));
    }

    private static int d(Context context, int n) {
        return Math.round(n * context.getResources().getDisplayMetrics().density);
    }

    static N500HomeSections attach(MainActivity activity, Handler handler, LinearLayout mainContent) {
        N500HomeSections instance = new N500HomeSections(activity, handler, mainContent);
        instance.refresh();
        return instance;
    }

    void refresh() {
        if (destroyed) return;
        // Restore header on main-menu return, then refresh shelves. All data
        // access is guarded; no fake provider entries displayed on error.
        setCollapsed(false);
        shelf.removeAllViews();
        contentIntents.clear();
        itemCount = 0;
        lastWatchReadable = false;
        List<MediaItem> watch = queryPrograms(true);
        List<MediaItem> suggested = queryPrograms(false);
        List<MediaItem> recent = loadRecentApps();
        List<MediaItem> streaming = loadStreamingApps();
        if (prefs.getBoolean(KEY_WATCH, true)) addShelf("CONTINUE WATCHING", watch, "WATCH NEXT", 6);
        if (prefs.getBoolean(KEY_RECOMMEND, true)) addShelf("RECOMMENDED BY YOUR APPS", suggested, "DISCOVER", 6);
        if (prefs.getBoolean(KEY_RECENT, true)) addShelf("RECENTLY OPENED", recent, "RECENT APPS", 7);
        if (prefs.getBoolean(KEY_APPS, true)) addShelf("STREAMING APPS", streaming, "YOUR APPS", 10);
        shelf.setVisibility(itemCount == 0 ? View.GONE : View.VISIBLE);
    }

    private GradientDrawable panel(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(d(activity, 13));
        drawable.setStroke(d(activity, 1), 0x44FFFFFF);
        return drawable;
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        return view;
    }

    private void addShelf(String title, List<MediaItem> items, String origin, int maxCount) {
        if (items.isEmpty()) return;
        int count = Math.min(maxCount, items.size());
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1,-2);
        outer.topMargin = d(activity,4);
        shelf.addView(block, outer);

        TextView titleView = label(title + "  •  " + origin, 11, SUBTLE);
        titleView.setLetterSpacing(0.09f);
        LinearLayout.LayoutParams tparams = new LinearLayout.LayoutParams(-1,-2);
        tparams.bottomMargin = d(activity,4);
        block.addView(titleView,tparams);

        HorizontalScrollView scroller = new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(false);
        scroller.setClipChildren(false);
        LinearLayout strip = new LinearLayout(activity);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setClipChildren(false);
        scroller.addView(strip);
        block.addView(scroller, new LinearLayout.LayoutParams(-1,d(activity,66)));
        for (int i = 0; i < count; i++) {
            MediaItem entry = items.get(i);
            TextView tile = label(entry.label, 12, WHITE);
            tile.setGravity(Gravity.CENTER);
            tile.setMaxLines(2);
            tile.setSingleLine(false);
            tile.setBackground(panel(0xCC26344B));
            tile.setPadding(d(activity,12), d(activity,6), d(activity,12), d(activity,6));
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setContentDescription(title + ", " + entry.label);
            tile.setOnFocusChangeListener((v, focus) -> {
                v.animate().scaleX(focus ? 1.035f : 1f).scaleY(focus ? 1.035f : 1f)
                        .setDuration(100).start();
                if (focus) scroller.smoothScrollTo(Math.max(0,v.getLeft()-d(activity,30)),0);
            });
            tile.setOnClickListener(v -> {
                try {
                    Intent launch = entry.intent;
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(launch);
                        if (entry.packageName != null)
                            RecentAppsManager.recordAppLaunch(activity, entry.packageName);
                    }
                } catch (Exception error) {
                    Toast.makeText(activity,"This content is not available",Toast.LENGTH_SHORT).show();
                }
            });
            tile.setOnKeyListener((v,key,event) -> {
                if (event.getAction()!=android.view.KeyEvent.ACTION_DOWN) return false;
                if (key==android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    activity.n500FocusFirstApp();
                    return true;
                }
                return false;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    d(activity,160),d(activity,61));
            lp.rightMargin = d(activity,7);
            strip.addView(tile,lp);
        }
        itemCount += count;
    }

    boolean focusShelf() {
        if (collapsed || shelf.getVisibility()!=View.VISIBLE) return false;
        // Take the first focusable item from the most relevant visible shelf.
        ArrayList<View> views = shelf.getFocusables(View.FOCUS_DOWN);
        if (views.isEmpty()) return false;
        views.get(0).requestFocus();
        return true;
    }

    void onTileFocus(int position, int columns) {
        boolean shouldHide = prefs.getBoolean(KEY_COLLAPSE,true) && position >= columns;
        if (shouldHide != collapsed) {
            // Deferring avoids changing grid measurements inside onTileFocused().
            handler.post(() -> { if (!destroyed) setCollapsed(shouldHide); });
        }
    }

    void onScrolled(int scrollOffset) {
        if (prefs.getBoolean(KEY_COLLAPSE,true) && scrollOffset>d(activity,95) && !collapsed) {
            handler.post(() -> { if (!destroyed) setCollapsed(true); });
        }
    }

    private void setCollapsed(boolean hide) {
        if (collapsed == hide) return;
        collapsed = hide;
        header.setVisibility(hide ? View.GONE : View.VISIBLE);
        shelf.setVisibility(hide || itemCount==0 ? View.GONE : View.VISIBLE);
    }

    void showSettings() {
        final String[] keys = {KEY_COLLAPSE,KEY_WATCH,KEY_RECOMMEND,KEY_RECENT,KEY_APPS};
        final String[] texts = {
                "Auto-hide header/weather when browsing deeper",
                "Continue Watching (only genuine Android TV data)",
                "Recommended (only genuine Android TV data)",
                "Recently opened apps",
                "Installed streaming apps"
        };
        boolean[] checked = new boolean[keys.length];
        for(int i=0;i<keys.length;i++) checked[i]=prefs.getBoolean(keys[i],true);
        new AlertDialog.Builder(activity,android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Home Layout & Streaming")
                .setMultiChoiceItems(texts,checked,(dialog,index,isChecked)->{
                    prefs.edit().putBoolean(keys[index],isChecked).apply();
                    refresh();
                })
                .setPositiveButton("Done",(dialog,which)->dialog.dismiss())
                .setNeutralButton("Refresh rows",(dialog,which)->refresh())
                .show();
    }

    void destroy() { destroyed=true; }

    private static final class MediaItem {
        final String label,packageName;
        final Intent intent;
        MediaItem(String label,String packageName,Intent intent) {
            this.label=label; this.packageName=packageName; this.intent=intent;
        }
    }

    private List<MediaItem> loadRecentApps() {
        // Use the existing launcher history; do not claim actual playback progress.
        String saved=activity.getSharedPreferences("recent_apps_prefs",Context.MODE_PRIVATE)
                .getString("recent_history","");
        if(saved.isEmpty()) return Collections.emptyList();
        PackageManager pm=activity.getPackageManager();
        List<MediaItem> list=new ArrayList<>();
        for (String name:saved.split(",")) {
            if(name.isEmpty() || name.equals(activity.getPackageName())) continue;
            try {
                Intent intent=pm.getLeanbackLaunchIntentForPackage(name);
                if(intent==null) intent=pm.getLaunchIntentForPackage(name);
                if(intent==null) continue;
                String label=String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(name,0)));
                list.add(new MediaItem(label,name,intent));
            } catch(Exception ignored) {}
            if(list.size()>=8)break;
        }
        return list;
    }

    private static boolean isStreaming(String name) {
        String s=name.toLowerCase(Locale.ROOT);
        String[] markers={"netflix","youtube","prime","disney","hulu","max","plex",
                "kodi","viu","iqiyi","wetv","iflix","loklok","astro","hbo",
                "spotify","tubi","crunchyroll","iflix","apple tv","video","stream",
                "media","cinema","movie","tvnow","mytv","rtm"};
        for(String item:markers) if(s.contains(item)) return true;
        return false;
    }

    private List<MediaItem> loadStreamingApps() {
        PackageManager pm=activity.getPackageManager();
        List<MediaItem> list=new ArrayList<>();
        Set<String> visited=new HashSet<>();
        for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}) {
            Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(category);
            try {
                List<ResolveInfo> apps=pm.queryIntentActivities(intent,0);
                for(ResolveInfo info:apps) {
                    if(info.activityInfo==null)continue;
                    String pkg=info.activityInfo.packageName;
                    if(pkg.equals(activity.getPackageName()) || !visited.add(pkg))continue;
                    String title=String.valueOf(info.loadLabel(pm));
                    if(!isStreaming(title+" "+pkg))continue;
                    Intent launch=pm.getLeanbackLaunchIntentForPackage(pkg);
                    if(launch==null)launch=pm.getLaunchIntentForPackage(pkg);
                    if(launch!=null)list.add(new MediaItem(title,pkg,launch));
                }
            } catch(Exception ignored) {}
        }
        list.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
        return list;
    }

    private List<MediaItem> queryPrograms(boolean watchNext) {
        ArrayList<MediaItem> list=new ArrayList<>();
        Uri uri=watchNext ? TvContract.WatchNextPrograms.CONTENT_URI : TvContract.PreviewPrograms.CONTENT_URI;
        // No guaranteed third-party read permission, so return nothing gracefully
        // on missing TvProvider, denied access, or empty Android TV channels.
        try(Cursor cursor=activity.getContentResolver().query(uri,new String[]{
                TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_INTENT_URI
        },null,null,null)) {
            lastWatchReadable = cursor!=null;
            if(cursor==null)return list;
            int titleIndex=cursor.getColumnIndex(TvContract.PreviewPrograms.COLUMN_TITLE);
            int uriIndex=cursor.getColumnIndex(TvContract.PreviewPrograms.COLUMN_INTENT_URI);
            Set<String> seen=new HashSet<>();
            while(cursor.moveToNext() && list.size()<8) {
                String title=titleIndex>=0?cursor.getString(titleIndex):null;
                String target=uriIndex>=0?cursor.getString(uriIndex):null;
                if(title==null || title.trim().isEmpty() || target==null || !seen.add(target))continue;
                Intent intent;
                try { intent=Intent.parseUri(target,Intent.URI_INTENT_SCHEME); }
                catch(Exception error) { continue; }
                if(intent.getComponent()==null && intent.getPackage()==null)continue;
                list.add(new MediaItem(title, intent.getPackage(),intent));
            }
        } catch(Exception ignored) {
            // Do not mislabel app shortcuts as Continue Watching.
        }
        return list;
    }
}
