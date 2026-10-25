package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Experimental screen capture *from* the N500 to an authenticated phone.
 *
 * Only starts after the Android screen-share permission dialog is approved.
 * Unprivileged MediaProjection: protected/DRM and HDMI overlays are not bypassed.
 * Captures a low-rate JPEG frame for the phone's Live View tab (not a recording).
 */
public final class N500CaptureService extends Service {
    public static final String ACTION_STOP = "com.n500.remote.STOP_VIEW";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    private static final String CHANNEL = "n500_live_view";
    private static final int NOTIFICATION_ID = 5173;
    private static final String TAG = "N500.LiveView";
    private static final int FRAME_WIDTH = 640;

    private static volatile byte[] latestJpeg;
    private static volatile boolean active;
    private HandlerThread thread;
    private Handler worker;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private long lastFrameAt;
    private boolean closing;

    public static boolean isActive() { return active; }
    public static byte[] frame() { return latestJpeg; }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int result = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent approved;
        if (Build.VERSION.SDK_INT >= 33) {
            approved = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            approved = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (result != android.app.Activity.RESULT_OK || approved == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "N500 Live View sharing", NotificationManager.IMPORTANCE_LOW));
        Intent stopIntent = new Intent(this, N500CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 42, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("N500 screen is being shared")
                .setContentText("To a paired phone on local Wi-Fi • tap Stop to end")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            startCapture(result, approved);
        } catch (Exception e) {
            Log.e(TAG, "Could not start MediaProjection", e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startCapture(int result, Intent data) {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(result, data);
        if (projection == null) {
            stopSelf();
            return;
        }
        thread = new HandlerThread("n500-live-jpeg");
        thread.start();
        worker = new Handler(thread.getLooper());

        final DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int height = Math.max(180, (int) ((long) FRAME_WIDTH * metrics.heightPixels / Math.max(1, metrics.widthPixels)));
        // Keep latency and RAM bounded on a low-power N500 CPU/GPU.
        height = Math.min(480, height);
        reader = ImageReader.newInstance(FRAME_WIDTH, height, PixelFormat.RGBA_8888, 2);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                // System has revoked sharing, user pressed Stop, or another capture began.
                stopSelf();
            }
        }, worker);
        reader.setOnImageAvailableListener(this::acceptFrame, worker);
        // One VirtualDisplay per approved MediaProjection session; never reuse consent.
        virtualDisplay = projection.createVirtualDisplay("N500 Live View",
                FRAME_WIDTH, height, Math.max(160, metrics.densityDpi),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, worker);
        active = true;
        latestJpeg = null;
    }

    private void acceptFrame(ImageReader imageReader) {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null || !active) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastFrameAt < 900) return;
            lastFrameAt = now;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer pixels = plane.getBuffer();
            int stride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int w = image.getWidth();
            int h = image.getHeight();
            if (pixelStride != 4 || w <= 0 || h <= 0) return;
            int paddedWidth = w + (stride - pixelStride * w) / pixelStride;
            if (paddedWidth < w || paddedWidth > 2048) return;

            Bitmap padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888);
            pixels.rewind();
            padded.copyPixelsFromBuffer(pixels);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, w, h);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(100_000);
            cropped.compress(Bitmap.CompressFormat.JPEG, 58, jpeg);
            cropped.recycle();
            padded.recycle();
            byte[] encoded = jpeg.toByteArray();
            if (encoded.length > 0 && encoded.length < 1_900_000) latestJpeg = encoded;
        } catch (Throwable t) {
            Log.w(TAG, "Skipping capture frame: " + t.getMessage());
        } finally {
            if (image != null) image.close();
        }
    }

    @Override public void onDestroy() {
        active = false;
        latestJpeg = null;
        if (!closing) {
            closing = true;
            if (reader != null) {
                try { reader.setOnImageAvailableListener(null, null); } catch (Exception ignored) {}
            }
            if (virtualDisplay != null) virtualDisplay.release();
            if (reader != null) reader.close();
            if (projection != null) {
                try { projection.stop(); } catch (Exception ignored) {}
            }
            if (thread != null) thread.quitSafely();
        }
        super.onDestroy();
    }
}
