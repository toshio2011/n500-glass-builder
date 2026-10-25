package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Purpose-built N500 Preview home: no stacked overlays, no arbitrary top-5 cap.
 *
 * Old pinned grid & floating hero remain in the upstream code for safe isolation,
 * but are hidden in this Preview. Real Watch Next programs display artwork when
 * available; absent provider data is never fabricated.
 */
final class N500Hub {
    private static final String PREFS = "n500_hub_favorites";
    private static final String FAV_KEY = "package_order";
    private static final int WHITE = Color.WHITE;
    private static final int MUTE = 0xFFB7CAD7;
    private static final int AQUA = 0xFF9BE7D7;
    private final MainActivity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final LinearLayout home;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final Set<String> streamingHints = new HashSet<>();
    private List<App> applications = new ArrayList<>();
    private List<Program> watchNext = new ArrayList<>();
    private List<Program> recommended = new ArrayList<>();
    private List<String> favorites = new ArrayList<>();
    private boolean appsPage;
    private boolean destroyed;
    private int generation;
    private View primaryFocus;

    private static final class App {
        final String pkg,label;
        final Drawable icon;
        final Intent launch;
        App(String pkg,String label, Drawable icon, Intent launch){
            this.pkg=pkg;this.label=label;this.icon=icon;this.launch=launch;
        }
    }
    private static final class Program {
        final String title,art;
        final Intent launch;
        Program(String title,String art,Intent launch){
            this.title=title;this.art=art;this.launch=launch;
        }
    }

    private N500Hub(MainActivity activity, LinearLayout home, View oldGrid,
                    View hero, View islamic, View floatingGlass) {
        this.activity = activity;
        this.home = home;
        this.prefs = activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        Collections.addAll(streamingHints,"netflix","youtube","disney","prime video",
                "amazon","hulu","plex","viu","iqiyi","wetv","astro","hbo",
                "apple tv","loklok","tubi","crunchyroll","movie","stream","kodi");
        oldGrid.setVisibility(View.GONE);
        if (hero != null) hero.setVisibility(View.GONE);
        if (islamic != null) islamic.setVisibility(View.GONE);
        if (floatingGlass != null) floatingGlass.setVisibility(View.GONE);
        // The main top bar remains in its normal measured layout, including
        // clock, compact weather, Wi-Fi and project settings.
        scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = column();
        body.setPadding(d(4),d(7),d(4),d(30));
        scroll.addView(body);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,0,1);
        params.topMargin=d(10);
        home.addView(scroll,params);
        favorites = readFavorites();
        render();
        reload();
    }

    static N500Hub attach(MainActivity activity, LinearLayout home, View grid,
                          View hero, View islamic, View glass) {
        return new N500Hub(activity,home,grid,hero,islamic,glass);
    }

    private int d(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
    private GradientDrawable background(int top, int bottom, int radius) {
        GradientDrawable draw = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,new int[]{top,bottom});
        draw.setCornerRadius(d(radius));
        draw.setStroke(d(1),0x6689AEBD);
        return draw;
    }
    private LinearLayout column() {
        LinearLayout l=new LinearLayout(activity);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        return l;
    }
    private LinearLayout row() {
        LinearLayout l=new LinearLayout(activity);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setClipChildren(false);
        return l;
    }
    private TextView text(String s,int size,int color,boolean bold) {
        TextView t=new TextView(activity);
        t.setText(s);t.setTextColor(color);t.setTextSize(size);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold)t.setTypeface(android.graphics.Typeface.DEFAULT,1);
        t.setIncludeFontPadding(false);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }
    private void space(LinearLayout parent,int h) {
        View v=new View(activity);
        parent.addView(v,new LinearLayout.LayoutParams(1,d(h)));
    }
    private void setFocusStyle(View tile) {
        tile.setFocusable(true);tile.setClickable(true);
        tile.setOnFocusChangeListener((view,focused)->{
            view.animate().scaleX(focused?1.045f:1f).scaleY(focused?1.045f:1f)
                    .setDuration(90).start();
        });
    }
    private void header(String label, String helper, Runnable action) {
        LinearLayout bar=row();
        TextView title=text(label,17,WHITE,true);
        bar.addView(title,new LinearLayout.LayoutParams(0,d(34),1));
        if (helper!=null) {
            TextView link=text(helper,12,AQUA,true);
            link.setGravity(Gravity.CENTER);
            link.setPadding(d(15),0,d(15),0);
            link.setBackground(background(0xC1234655,0xBB172C42,13));
            setFocusStyle(link);
            link.setOnClickListener(v->action.run());
            bar.addView(link,new LinearLayout.LayoutParams(-2,d(34)));
            if(primaryFocus==null)primaryFocus=link;
        }
        body.addView(bar);
        space(body,8);
    }

    private List<String> readFavorites() {
        String raw=prefs.getString(FAV_KEY,null);
        if (raw==null) return new ArrayList<>();
        List<String> list=new ArrayList<>();
        for(String item:raw.split("\n")) if(!item.isEmpty()&&!list.contains(item)) list.add(item);
        return list;
    }
    private void persist() {
        prefs.edit().putString(FAV_KEY,android.text.TextUtils.join("\n",favorites)).apply();
    }
    private App appFor(String pkg) {
        for(App a:applications)if(a.pkg.equals(pkg))return a;
        return null;
    }
    private boolean streaming(App app) {
        String str=(app.label+" "+app.pkg).toLowerCase(Locale.ROOT);
        for(String hint:streamingHints)if(str.contains(hint))return true;
        return false;
    }
    private void render() {
        if (destroyed)return;
        primaryFocus=null;
        body.removeAllViews();
        if (appsPage) renderAllApps(); else renderHome();
    }
    private void renderHome() {
        header("FAVORITES","＋ Add apps",()->openApps());
        List<App> pinned=new ArrayList<>();
        for(String pkg:favorites){App a=appFor(pkg);if(a!=null)pinned.add(a);}
        if(pinned.isEmpty()) {
            TextView empty=text("Pin your favorite apps here. No fixed limit.",15,MUTE,false);
            empty.setPadding(d(12),d(14),d(12),d(14));
            empty.setBackground(background(0xCC1C3446,0xCC14283A,14));
            empty.setFocusable(true);
            empty.setOnClickListener(v->openApps());
            setFocusStyle(empty);
            body.addView(empty,new LinearLayout.LayoutParams(-1,d(65)));
            primaryFocus=empty;
        } else {
            LinearLayout strip=horizontalStrip();
            for(App app:pinned) addAppTile(strip,app,true);
        }
        space(body,23);

        if(!watchNext.isEmpty()) {
            header("CONTINUE WATCHING",null,null);
            LinearLayout strip=horizontalStrip();
            for(Program entry:watchNext) addPosterTile(strip,entry);
            space(body,23);
        }
        if(!recommended.isEmpty()) {
            header("RECOMMENDED",null,null);
            LinearLayout strip=horizontalStrip();
            for(Program entry:recommended) addPosterTile(strip,entry);
            space(body,23);
        }

        List<App> streaming=new ArrayList<>();
        for(App app:applications) if(streaming(app)) streaming.add(app);
        if(!streaming.isEmpty()) {
            header("STREAMING","All apps",()->openApps());
            LinearLayout strip=horizontalStrip();
            for(int i=0;i<Math.min(10,streaming.size());i++) addAppTile(strip,streaming.get(i),false);
            space(body,14);
        }
        header("ALL APPS","Browse & manage",()->openApps());
        TextView browse=text("Browse installed apps  •  Add, remove and reorder Favorites",13,MUTE,false);
        browse.setPadding(d(7),d(4),0,d(5));
        browse.setFocusable(true);
        browse.setOnClickListener(v->openApps());
        setFocusStyle(browse);
        body.addView(browse);
        if (primaryFocus==null)primaryFocus=browse;
    }
    private LinearLayout horizontalStrip() {
        HorizontalScrollView hs=new HorizontalScrollView(activity);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout strip=row();
        hs.addView(strip);
        body.addView(hs,new LinearLayout.LayoutParams(-1,-2));
        return strip;
    }
    private void addAppTile(LinearLayout strip,App app,boolean favorite) {
        LinearLayout tile=column();
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(background(0xDB263E4D,0xCC0B1E30,14));
        tile.setPadding(d(11),d(7),d(11),d(6));
        ImageView art=new ImageView(activity);
        art.setImageDrawable(app.icon);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(art,new LinearLayout.LayoutParams(d(45),d(39)));
        space(tile,6);
        TextView label=text(app.label,11,WHITE,true);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        tile.addView(label,new LinearLayout.LayoutParams(-1,d(19)));
        setFocusStyle(tile);
        tile.setContentDescription(app.label+(favorite?", favorite":""));
        tile.setOnClickListener(v->launch(app.launch,app.pkg));
        tile.setOnLongClickListener(v->{manageFavorite(app);return true;});
        tile.setOnKeyListener((v,key,event)->{
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(key==KeyEvent.KEYCODE_MENU) {manageFavorite(app);return true;}
            return false;
        });
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(d(128),d(88));
        lp.rightMargin=d(11);
        strip.addView(tile,lp);
        if(primaryFocus==null)primaryFocus=tile;
    }
    private void addPosterTile(LinearLayout strip,Program program) {
        LinearLayout tile=column();
        tile.setBackground(background(0xC01B3044,0xE00D1D30,12));
        ImageView poster=new ImageView(activity);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(background(0xFF365A69,0xFF1B2D40,11));
        // A real provider artwork URI, not fabricated thumbnails.
        if(program.art!=null&&!program.art.isEmpty())loadArtwork(poster,program.art);
        tile.addView(poster,new LinearLayout.LayoutParams(-1,d(101)));
        TextView caption=text(program.title,12,WHITE,true);
        caption.setMaxLines(1);
        caption.setPadding(d(9),0,d(6),0);
        tile.addView(caption,new LinearLayout.LayoutParams(-1,d(25)));
        setFocusStyle(tile);
        tile.setOnClickListener(v->launch(program.launch,null));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(d(192),d(128));
        lp.rightMargin=d(12);
        strip.addView(tile,lp);
        if(primaryFocus==null)primaryFocus=tile;
    }
    private void loadArtwork(ImageView view,String source) {
        worker.execute(()->{
            Bitmap bmp=null;
            try {
                Uri uri=Uri.parse(source);
                String scheme=uri.getScheme();
                if (!"content".equals(scheme)&&!"https".equals(scheme))return;
                try(InputStream stream="content".equals(scheme)
                        ? activity.getContentResolver().openInputStream(uri)
                        : new URL(source).openStream()) {
                    if(stream!=null) {
                        BitmapFactory.Options opts=new BitmapFactory.Options();
                        opts.inSampleSize=2;opts.inPreferredConfig=Bitmap.Config.RGB_565;
                        bmp=BitmapFactory.decodeStream(stream,null,opts);
                    }
                }
            } catch(Exception|OutOfMemoryError ignored) {}
            final Bitmap image=bmp;
            ui.post(()->{
                if(destroyed)return;
                if(view.isAttachedToWindow()&&image!=null)view.setImageBitmap(image);
            });
        });
    }
    private void renderAllApps() {
        header("ALL APPS","‹ Back to Home",()->backHome());
        TextView help=text("Select an app to open or add to Favorites. Long-press a favorite to reorder.",12,MUTE,false);
        help.setPadding(0,0,0,d(12));
        body.addView(help);
        final int columns=4;
        LinearLayout line=null;
        for(int i=0;i<applications.size();i++) {
            if(i%columns==0) {
                line=row();
                body.addView(line,new LinearLayout.LayoutParams(-1,-2));
                space(body,10);
            }
            App app=applications.get(i);
            final boolean favorite=favorites.contains(app.pkg);
            LinearLayout tile=column();
            tile.setBackground(background(favorite?0xFF225A59:0xDD1B3446,0xD00D1B2C,13));
            tile.setGravity(Gravity.CENTER);
            ImageView image=new ImageView(activity);
            image.setImageDrawable(app.icon);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(image,new LinearLayout.LayoutParams(d(40),d(35)));
            TextView label=text(app.label,12,WHITE,true);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            tile.addView(label,new LinearLayout.LayoutParams(-1,d(24)));
            if(favorite){
                TextView pinned=text("★ Favorite",10,AQUA,false);
                pinned.setGravity(Gravity.CENTER);
                tile.addView(pinned);
            }
            setFocusStyle(tile);
            tile.setOnClickListener(v->manageFavorite(app));
            tile.setOnLongClickListener(v->{launch(app.launch,app.pkg);return true;});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,d(91),1f);
            lp.rightMargin=d(10);
            line.addView(tile,lp);
            if(primaryFocus==null)primaryFocus=tile;
        }
        if(applications.isEmpty())body.addView(text("Loading your installed apps…",13,MUTE,false));
    }
    private void manageFavorite(App app) {
        boolean pinned=favorites.contains(app.pkg);
        String[] options=pinned
                ?new String[]{"Open app","Remove from Favorites","Move left","Move right"}
                :new String[]{"Add to Favorites","Open app"};
        new AlertDialog.Builder(activity,android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label).setItems(options,(dialog,which)->{
                    String action=options[which];
                    if(action.equals("Open app")) launch(app.launch,app.pkg);
                    else if(action.equals("Add to Favorites")) {
                        favorites.add(app.pkg);persist();render();
                    } else if(action.equals("Remove from Favorites")) {
                        favorites.remove(app.pkg);persist();render();
                    } else if(action.equals("Move left")||action.equals("Move right")) {
                        int pos=favorites.indexOf(app.pkg);
                        int dest=pos+(action.equals("Move left")?-1:1);
                        if(pos>=0&&dest>=0&&dest<favorites.size()) {
                            Collections.swap(favorites,pos,dest);persist();render();
                        }
                    }
                }).setNegativeButton("Cancel",null).show();
    }
    void openApps() {
        appsPage=true;render();scroll.scrollTo(0,0);restoreFocus();
    }
    boolean backHome() {
        if(!appsPage)return false;
        appsPage=false;render();scroll.scrollTo(0,0);restoreFocus();
        return true;
    }
    void restoreFocus() {
        scroll.post(()->{
            if(destroyed)return;
            if(primaryFocus!=null)primaryFocus.requestFocus();
        });
    }
    void reload() {
        if(destroyed)return;
        final int expected=++generation;
        worker.execute(()->{
            List<App> apps=getApps();
            List<Program> watch=getPrograms(true);
            List<Program> rec=getPrograms(false);
            ui.post(()->{
                if(destroyed||expected!=generation)return;
                applications=apps;
                watchNext=watch;
                recommended=rec;
                render();
                restoreFocus();
            });
        });
    }
    private List<App> getApps() {
        PackageManager pm=activity.getPackageManager();
        Set<String> visited=new HashSet<>();
        List<App> found=new ArrayList<>();
        for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}) {
            Intent query=new Intent(Intent.ACTION_MAIN).addCategory(category);
            try {
                for(ResolveInfo info:pm.queryIntentActivities(query,0)) {
                    if(info.activityInfo==null)continue;
                    String pkg=info.activityInfo.packageName;
                    if(!visited.add(pkg)||pkg.equals(activity.getPackageName()))continue;
                    Intent launch=pm.getLeanbackLaunchIntentForPackage(pkg);
                    if(launch==null)launch=pm.getLaunchIntentForPackage(pkg);
                    if(launch==null)continue;
                    found.add(new App(pkg,String.valueOf(info.loadLabel(pm)),info.loadIcon(pm),launch));
                }
            } catch(Exception ignored) {}
        }
        found.sort(Comparator.comparing(a->a.label.toLowerCase(Locale.ROOT)));
        // No arbitrary top 5. Seed from actual existing Glass dock configuration
        // only when new Favorites preferences do not exist yet.
        if(!prefs.contains(FAV_KEY)) {
            List<String> seed=new ArrayList<>();
            for(String key:Config.load(activity).dockApps) {
                String pkg=key.contains("/")?key.substring(0,key.indexOf("/")):key;
                for(App a:found)if(a.pkg.equals(pkg)&&!seed.contains(pkg))seed.add(pkg);
            }
            ui.post(()->{
                if(destroyed||prefs.contains(FAV_KEY))return;
                favorites=seed;persist();render();
            });
        }
        return found;
    }
    private List<Program> getPrograms(boolean watching) {
        Uri uri=watching?TvContract.WatchNextPrograms.CONTENT_URI
                :TvContract.PreviewPrograms.CONTENT_URI;
        List<Program> result=new ArrayList<>();
        try(Cursor cursor=activity.getContentResolver().query(uri,new String[]{
                TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI
        },null,null,null)) {
            if(cursor==null)return result;
            Set<String> seen=new HashSet<>();
            while(cursor.moveToNext()&&result.size()<12) {
                String title=cursor.getString(0);
                String target=cursor.getString(1);
                String poster=cursor.getString(2);
                String thumbnail=cursor.getString(3);
                if(title==null||title.isEmpty()||target==null||!seen.add(target))continue;
                try{
                    Intent intent=Intent.parseUri(target,Intent.URI_INTENT_SCHEME);
                    if(intent.getPackage()==null&&intent.getComponent()==null)continue;
                    String art=thumbnail!=null&&!thumbnail.isEmpty()?thumbnail:poster;
                    result.add(new Program(title,art,intent));
                }catch(Exception ignored){}
            }
        } catch(Exception ignored) { /* No provider permission means NO fake content. */ }
        return result;
    }
    private void launch(Intent intent,String pkg) {
        if(intent==null)return;
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            if(pkg!=null)RecentAppsManager.recordAppLaunch(activity,pkg);
        } catch(Exception e) {
            Toast.makeText(activity,"This item cannot be opened.",Toast.LENGTH_SHORT).show();
        }
    }
    void destroy() {
        destroyed=true;
        generation++;
        worker.shutdownNow();
    }
}
