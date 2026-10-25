package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.view.KeyEvent;
import android.util.Log;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Experimental companion service, manually enabled from Settings > Phone Link.
 *
 * Distinct from the upstream wallpaper server; never shares its unauthenticated
 * upload API. 6-digit short-lived pairing followed by an ephemeral 256-bit token.
 * DEV WARNING: transport is plaintext LAN HTTP; do NOT forward port or use on
 * an untrusted network. TLS/pinned certificates must precede any stable release.
 */
public final class N500RemoteServer {
    private static final String TAG = "N500.PhoneLink";
    private static final int PORT = 8765;
    private final WeakReference<MainActivity> target;
    private final Handler main;
    private final SecureRandom random = new SecureRandom();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 2, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(12),
            new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean running;
    private volatile String pin;
    private volatile long pinExpiresAt;
    private volatile String token;
    private volatile boolean paired;
    private ServerSocket listener;
    private Thread acceptThread;

    public N500RemoteServer(MainActivity activity, Handler handler) {
        target = new WeakReference<>(activity);
        main = handler;
    }

    public synchronized boolean start() {
        if (running) return true;
        try {
            listener = new ServerSocket(PORT);
            listener.setReuseAddress(true);
            running = true;
            rotatePairingCode();
            acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket incoming = listener.accept();
                        // Only local private or loopback hosts. This is not a
                        // substitute for encryption but prevents internet peers.
                        if (!incoming.getInetAddress().isSiteLocalAddress() &&
                                !incoming.getInetAddress().isLoopbackAddress()) {
                            incoming.close();
                            continue;
                        }
                        try { workers.execute(() -> serve(incoming)); }
                        catch (Exception e) { incoming.close(); }
                    } catch (Exception e) {
                        if (running) Log.w(TAG, "accept failed", e);
                    }
                }
            }, "n500-phone-link");
            acceptThread.start();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Unable to listen on port 8765", e);
            running = false;
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        paired = false;
        pin = null;
        token = null;
        try { if (listener != null) listener.close(); } catch (Exception ignored) {}
        workers.shutdownNow();
    }

    public synchronized String rotatePairingCode() {
        // Revokes all existing clients and produces short-lived code.
        token = null;
        paired = false;
        pin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        pinExpiresAt = System.currentTimeMillis() + 120_000;
        return pin;
    }

    public String pairingCode() { return pin; }
    public String address() {
        return WallpaperUploadServer.getLocalIpAddress() + ":" + PORT;
    }
    public boolean isPaired() { return paired; }

    private static byte[] readBody(InputStream in, int length) throws Exception {
        if (length < 0 || length > 512) throw new IllegalArgumentException("Invalid body size");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        while (result.size() < length) {
            int n = in.read(buf, 0, Math.min(buf.length, length - result.size()));
            if (n < 0) throw new IllegalArgumentException("Short body");
            result.write(buf, 0, n);
        }
        return result.toByteArray();
    }

    private static String headers(InputStream in) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        int prev3=0,prev2=0,prev1=0,c;
        while ((c = in.read()) != -1) {
            bytes.write(c);
            if (bytes.size() > 6000) throw new IllegalArgumentException("Request headers too large");
            if (prev3==13 && prev2==10 && prev1==13 && c==10) break;
            prev3=prev2; prev2=prev1; prev1=c;
        }
        return bytes.toString("UTF-8");
    }

    private static void reply(OutputStream out, int code, String type, byte[] data) throws Exception {
        String name;
        switch (code) {
            case 200: name="OK"; break;
            case 202: name="Accepted"; break;
            case 204: name="No Content"; break;
            case 400: name="Bad Request"; break;
            case 401: name="Unauthorized"; break;
            case 403: name="Forbidden"; break;
            case 404: name="Not Found"; break;
            case 409: name="Conflict"; break;
            default: name="Service Unavailable"; break;
        }
        String headers="HTTP/1.1 "+code+" "+name+"\r\n"+
                "Content-Type: "+type+"\r\n"+
                "Content-Length: "+data.length+"\r\n"+
                "Cache-Control: no-store\r\n"+
                "X-Content-Type-Options: nosniff\r\n"+
                "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }
    private static void json(OutputStream out,int code,String message) throws Exception {
        JSONObject j=new JSONObject();
        j.put("message", message);
        reply(out,code,"application/json; charset=UTF-8",
                j.toString().getBytes(StandardCharsets.UTF_8));
    }

    private boolean authorized(String candidate) {
        if (!paired || token==null || candidate==null || !candidate.startsWith("Bearer ")) return false;
        byte[] a=token.getBytes(StandardCharsets.US_ASCII);
        byte[] b=candidate.substring(7).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(a,b);
    }

    private void serve(Socket socket) {
        try (Socket peer=socket) {
            peer.setSoTimeout(4000);
            InputStream in=peer.getInputStream();
            OutputStream out=peer.getOutputStream();
            String head=headers(in);
            String[] lines=head.split("\r\n");
            if (lines.length < 1) return;
            String[] request=lines[0].split(" ");
            if (request.length < 2) return;
            String method=request[0], path=request[1], authorization=null;
            int bodyLength=0;
            for (int i=1;i<lines.length;i++) {
                int sep=lines[i].indexOf(':');
                if (sep<1) continue;
                String field=lines[i].substring(0,sep).trim().toLowerCase(Locale.ROOT);
                String value=lines[i].substring(sep+1).trim();
                if ("authorization".equals(field)) authorization=value;
                else if ("content-length".equals(field)) bodyLength=Integer.parseInt(value);
            }
            if (bodyLength>512) { json(out,400,"Body too large"); return; }
            String body=new String(readBody(in,bodyLength),StandardCharsets.UTF_8);

            if ("POST".equals(method) && "/v1/pair".equals(path)) {
                JSONObject requestBody=new JSONObject(body);
                String supplied=requestBody.optString("pin","");
                if (!running || pin == null || System.currentTimeMillis()>pinExpiresAt || paired ||
                        !MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8),
                                supplied.getBytes(StandardCharsets.UTF_8))) {
                    json(out,403,"Code expired or invalid. Reopen Phone Link on TV.");
                    return;
                }
                byte[] secret=new byte[32];
                random.nextBytes(secret);
                StringBuilder hex=new StringBuilder();
                for (byte b:secret) hex.append(String.format(Locale.ROOT,"%02x",b & 0xff));
                token=hex.toString();
                paired=true;
                pin=null;
                // Close the on-projector PIN dialog once a phone is paired.
                // Leaving it open steals Activity window focus, rejecting D-pad keys.
                main.post(() -> {
                    MainActivity current = target.get();
                    if (current != null) current.n500PairingCompleted();
                });
                JSONObject response=new JSONObject();
                response.put("token",token);
                response.put("message","Paired with Glass TV N500 Preview");
                reply(out,200,"application/json; charset=UTF-8",
                        response.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (!authorized(authorization)) { json(out,401,"Pair with projector first"); return; }
            if ("GET".equals(method) && "/v1/apps".equals(path)) {
                MainActivity activity=target.get();
                if (activity==null) { json(out,409,"Projector Preview is closed"); return; }
                android.content.pm.PackageManager pm=activity.getPackageManager();
                org.json.JSONArray found=new org.json.JSONArray();
                java.util.HashSet<String> seen=new java.util.HashSet<>();
                for (String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}) {
                    Intent query=new Intent(Intent.ACTION_MAIN).addCategory(category);
                    for(android.content.pm.ResolveInfo app:pm.queryIntentActivities(query,0)) {
                        if(app.activityInfo==null) continue;
                        String pkg=app.activityInfo.packageName;
                        if(!seen.add(pkg) || pkg.equals(activity.getPackageName()))continue;
                        String label=String.valueOf(app.loadLabel(pm));
                        org.json.JSONObject record=new org.json.JSONObject();
                        record.put("label",label); record.put("package",pkg);
                        found.put(record);
                        if(found.length()>=90)break;
                    }
                }
                org.json.JSONObject response=new org.json.JSONObject();
                response.put("apps",found);
                reply(out,200,"application/json; charset=UTF-8",
                        response.toString().getBytes(StandardCharsets.UTF_8));
            } else if ("POST".equals(method) && "/v1/launch".equals(path)) {
                MainActivity activity=target.get();
                if(activity==null) { json(out,409,"Projector Preview is closed"); return; }
                String pkg=new org.json.JSONObject(body).optString("package","");
                if(!pkg.matches("[a-zA-Z0-9_.]{3,180}") || pkg.equals(activity.getPackageName())) {
                    json(out,400,"Invalid application");return;
                }
                // Only launch apps Android itself advertises as user-launchable.
                android.content.pm.PackageManager pm=activity.getPackageManager();
                Intent launch=pm.getLeanbackLaunchIntentForPackage(pkg);
                if(launch==null)launch=pm.getLaunchIntentForPackage(pkg);
                if(launch==null) { json(out,404,"App not launchable or not installed"); return; }
                final Intent safe=launch;
                main.post(() -> {
                    try {
                        safe.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(safe);
                        RecentAppsManager.recordAppLaunch(activity,pkg);
                    } catch(Exception error){ Log.w(TAG,"App launch failed",error); }
                });
                json(out,200,"Opening "+pkg+" on projector");
            } else             if ("GET".equals(method) && "/v1/status".equals(path)) {
                JSONObject j=new JSONObject();
                j.put("name","Glass TV N500 Preview");
                j.put("version","0.1-dev");
                j.put("live",N500CaptureService.isActive());
                j.put("remoteScope","launcher-only");
                MainActivity preview = target.get();
                j.put("previewFocused", preview != null && preview.hasWindowFocus());
                reply(out,200,"application/json; charset=UTF-8",
                        j.toString().getBytes(StandardCharsets.UTF_8));
            } else if ("POST".equals(method) && "/v1/key".equals(path)) {
                JSONObject requestBody=new JSONObject(body);
                String key=requestBody.optString("key","");
                MainActivity activity=target.get();
                if (activity==null) { json(out,409,"TV Home is not available"); return; }
                if ("HOME".equals(key)) {
                    main.post(() -> {
                        Intent home=new Intent(Intent.ACTION_MAIN);
                        home.addCategory(Intent.CATEGORY_HOME);
                        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(home);
                    });
                    json(out,200,"Return to projector Home requested");
                } else if ("VOL_UP".equals(key)||"VOL_DOWN".equals(key)||"MUTE".equals(key)) {
                    main.post(() -> {
                        AudioManager audio=(AudioManager)activity.getSystemService(Activity.AUDIO_SERVICE);
                        if (audio==null) return;
                        if ("MUTE".equals(key)) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                        else audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                "VOL_UP".equals(key)?AudioManager.ADJUST_RAISE:AudioManager.ADJUST_LOWER,
                                AudioManager.FLAG_SHOW_UI);
                    });
                    json(out,200,"Audio adjustment requested (device support varies)");
                } else {
                    int code=keyCode(key);
                    if (code<0) { json(out,400,"Unsupported remote key"); return; }
                    if (!activity.hasWindowFocus()) {
                        json(out,409,"Open Glass TV Preview on N500 and close its pairing popup. This first remote can only control Preview.");
                        return;
                    }
                    main.post(() -> {
                        long now=android.os.SystemClock.uptimeMillis();
                        activity.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0));
                        activity.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_UP,code,0));
                    });
                    json(out,200,"Sent "+key+" to Glass TV Preview");
                }
            } else if ("POST".equals(method) && "/v1/live/start".equals(path)) {
                MainActivity activity=target.get();
                if (activity==null || !activity.hasWindowFocus()) {
                    json(out,409,"Open the N500 Preview app on the projector to approve screen sharing");
                    return;
                }
                if (N500CaptureService.isActive()) { json(out,200,"Live View already active"); return; }
                main.post(activity::n500RequestLive);
                json(out,202,"Approve Android screen sharing on the projector");
            } else if ("POST".equals(method) && "/v1/live/stop".equals(path)) {
                MainActivity activity=target.get();
                if (activity!=null) {
                    Intent stop=new Intent(activity,N500CaptureService.class).setAction(N500CaptureService.ACTION_STOP);
                    activity.startService(stop);
                }
                json(out,200,"Stop sharing requested");
            } else if ("GET".equals(method) && "/v1/live/frame".equals(path)) {
                byte[] frame=N500CaptureService.frame();
                if (frame==null || !N500CaptureService.isActive()) {
                    reply(out,204,"image/jpeg",new byte[0]);
                } else {
                    reply(out,200,"image/jpeg",frame);
                }
            } else {
                json(out,404,"Unknown companion endpoint");
            }
        } catch (Exception e) {
            Log.w(TAG,"Client failed: "+e.getMessage());
        }
    }

    private int keyCode(String value) {
        switch(value) {
            case "UP": return KeyEvent.KEYCODE_DPAD_UP;
            case "DOWN": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "LEFT": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "RIGHT": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "OK": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "BACK": return KeyEvent.KEYCODE_BACK;
            case "INPUT": return KeyEvent.KEYCODE_TV_INPUT;
            default: return -1;
        }
    }
}
