package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * N500-only GitHub Releases updater, for the experimental projector Preview.
 *
 * - Metadata MUST be attached to an actual release in OUR repository.
 * - No third-party/upstream APK, no silently installed update.
 * - Checks APK SHA256, Android package ID, versionCode and signing certificate.
 * - A signing mismatch cannot be "worked around" from inside the launcher:
 *   explain the preview-only manual migration instead of auto-uninstalling.
 */
public final class AppUpdateManager {
    private static final String OWNER_REPO = "toshio2011/n500-glass-builder";
    private static final String RELEASES_URL =
            "https://api.github.com/repos/" + OWNER_REPO + "/releases?per_page=20";
    private static final String ASSET_NAME = "n500-PROJECTOR-update.json";
    private static final String PACKAGE_NAME = "com.n500.glasstvhome.preview";
    private static final String RELEASE_PREFIX =
            "https://github.com/" + OWNER_REPO + "/releases/download/";
    private static final int MAX_METADATA = 120_000;
    private static final int MAX_APK = 28 * 1024 * 1024;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private AppUpdateManager() {}

    public interface CheckCallback {
        void onResult(boolean hasUpdate, String newVersionName, String changelog, String apkUrl);
    }

    static final class Release {
        String tag, versionName, apkUrl, webUrl, sha256, signerSha256, notes;
        int versionCode;
    }

    static void alert(Activity activity, String title, String message) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setMessage(message)
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss()).show();
    }

    public static void checkForUpdates(Activity activity, boolean manualCheck) {
        if (activity == null || activity.isFinishing()) return;
        if (!manualCheck) return; // Preview never pops up unsolicited update dialogs at boot.
        Toast.makeText(activity, "Checking OUR N500 GitHub Releases…", Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            Release available = null;
            String error = null;
            int currentVersion = 0;
            String currentName = "?";
            try {
                if (!PACKAGE_NAME.equals(activity.getPackageName()))
                    throw new IllegalStateException("Only N500 Preview can use this update channel");
                PackageInfo current = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0);
                currentVersion = current.versionCode;
                currentName = current.versionName;
                available = findNewest(currentVersion);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final Release newest = available;
            final String issue = error;
            final int version = currentVersion;
            final String name = currentName;
            UI.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (issue != null) {
                    alert(activity, "Couldn't check GitHub",
                            "Our release feed was unavailable: " + issue +
                            "\n\nNo files were changed.");
                } else if (newest == null) {
                    alert(activity, "Glass TV N500 Preview",
                            "No newer compatible Preview release found on our GitHub.\n\n" +
                            "Installed: " + name + " (build " + version + ").\n\n" +
                            "This does not check the original Glass TV developer's updates.");
                } else {
                    showRelease(activity, newest, name);
                }
            });
        });
    }

    private static Release findNewest(int currentVersion) throws Exception {
        JSONArray releases = new JSONArray(readString(RELEASES_URL, 600_000));
        Release best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft", true) ||
                    !release.optBoolean("prerelease", false)) continue;
            String tag = release.optString("tag_name", "");
            if (!tag.startsWith("n500-v") || !tag.contains("preview")) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;

            JSONObject metadata = null, apk = null;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.optJSONObject(j);
                if (asset == null) continue;
                if (ASSET_NAME.equals(asset.optString("name"))) metadata = asset;
            }
            if (metadata == null) continue;
            String metaUrl = metadata.optString("browser_download_url", "");
            if (!metaUrl.startsWith(RELEASE_PREFIX + tag + "/")) continue;
            JSONObject m;
            try {
                m = new JSONObject(readString(metaUrl, MAX_METADATA));
            } catch (Exception ignored) {
                // Skip malformed/broken prerelease; never fall back to upstream.
                continue;
            }
            if (!PACKAGE_NAME.equals(m.optString("packageName")) ||
                    !tag.equals(m.optString("releaseTag"))) continue;
            int code = m.optInt("versionCode", 0);
            if (code <= currentVersion || (best != null && code <= best.versionCode)) continue;

            String file = m.optString("apkAsset", "");
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.optJSONObject(j);
                if (asset != null && file.equals(asset.optString("name"))) apk = asset;
            }
            if (apk == null || apk.optLong("size", 0) <= 0 ||
                    apk.optLong("size", 0) > MAX_APK) continue;
            String url = apk.optString("browser_download_url", "");
            String sha = m.optString("sha256", "").toLowerCase(Locale.ROOT);
            String signer = m.optString("signerSha256", "").toLowerCase(Locale.ROOT);
            if (!url.startsWith(RELEASE_PREFIX + tag + "/") ||
                    !sha.matches("[0-9a-f]{64}") ||
                    !signer.matches("[0-9a-f]{64}")) continue;
            // GitHub's asset digest must agree with release metadata.
            if (!("sha256:" + sha).equalsIgnoreCase(apk.optString("digest", ""))) continue;

            Release candidate = new Release();
            candidate.tag = tag;
            candidate.versionCode = code;
            candidate.versionName = m.optString("versionName", "new release");
            candidate.apkUrl = url;
            candidate.webUrl = release.optString("html_url", "");
            candidate.sha256 = sha;
            candidate.signerSha256 = signer;
            candidate.notes = m.optString("changelog", "New Glass TV preview improvements.");
            best = candidate;
            apk = null;
        }
        return best;
    }

    private static String readString(String url, int limit) throws Exception {
        byte[] bytes = download(url, limit, null, null);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Download bytes only from trusted HTTPS GitHub URLs (redirects for release
     * assets may legitimately go to GitHub's cloud storage).
     */
    private static byte[] download(String location, int maxBytes,
                                   File destination, MessageDigest digest) throws Exception {
        if (!location.startsWith("https://")) throw new IllegalArgumentException("HTTPS required");
        HttpURLConnection connection = (HttpURLConnection) new URL(location).openConnection();
        connection.setConnectTimeout(9000);
        connection.setReadTimeout(18000);
        connection.setRequestProperty("User-Agent", "Glass-TV-N500-Preview-Updater");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setUseCaches(false);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException("GitHub HTTP " + code);
            long declared = connection.getContentLengthLong();
            if (declared > maxBytes) throw new IllegalStateException("Download is unexpectedly large");

            ByteArrayOutputStream memory = destination == null ? new ByteArrayOutputStream() : null;
            try (InputStream input = connection.getInputStream();
                 java.io.OutputStream output = destination == null ? memory : new FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > maxBytes) throw new IllegalStateException("Download exceeded safe size");
                    if (digest != null) digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            return memory == null ? new byte[0] : memory.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b & 255));
        return sb.toString();
    }

    private static String certificateDigest(Signature signature) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()));
    }

    @SuppressWarnings("deprecation")
    private static String signer(PackageInfo packageInfo) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (packageInfo.signingInfo == null) throw new IllegalStateException("No APK signer info");
            signatures = packageInfo.signingInfo.hasMultipleSigners()
                    ? packageInfo.signingInfo.getApkContentsSigners()
                    : packageInfo.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = packageInfo.signatures;
        }
        if (signatures == null || signatures.length != 1)
            throw new IllegalStateException("Expected one signing certificate");
        return certificateDigest(signatures[0]);
    }

    private static int packageFlags() {
        return Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    private static void showRelease(Activity activity, Release update, String currentName) {
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Glass TV Preview update available")
            .setMessage("Installed: " + currentName + "\nNew: " + update.versionName
                    + " (build " + update.versionCode + ")\n\n" + update.notes
                    + "\n\nWe will verify the APK and its signing certificate before opening Android's installer."
                    + "\nA different CI signing key means a manual Preview reinstall is required.")
            .setPositiveButton("Download & verify", (d, which) -> verifyDownload(activity, update))
            .setNeutralButton("View on GitHub", (d, which) -> openRelease(activity, update.webUrl))
            .setNegativeButton("Not now", (d, which) -> d.dismiss())
            .show();
    }

    private static void openRelease(Activity activity, String url) {
        if (!url.startsWith("https://github.com/" + OWNER_REPO + "/releases/")) {
            alert(activity, "Blocked", "Unexpected GitHub release link");
            return;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            alert(activity, "Browser unavailable", "Open our GitHub Releases on your phone.");
        }
    }

    private static void verifyDownload(Activity activity, Release update) {
        Toast.makeText(activity, "Verifying N500 APK…", Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            File apk = new File(activity.getCacheDir(), "n500-verified-update.apk");
            String problem = null;
            try {
                String currentSigner = signer(activity.getPackageManager().getPackageInfo(
                        activity.getPackageName(), packageFlags()));
                if (!currentSigner.equalsIgnoreCase(update.signerSha256)) {
                    throw new SecurityException("SIGNING_KEY_CHANGED: Android cannot install this build "
                            + "over your current Preview without removing the old Preview first.");
                }

                if (apk.exists() && !apk.delete()) {
                    throw new IllegalStateException("Could not clear old update file");
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                download(update.apkUrl, MAX_APK, apk, digest);
                if (!update.sha256.equals(hex(digest.digest())))
                    throw new SecurityException("APK checksum mismatch; download rejected");

                PackageInfo archive = activity.getPackageManager()
                        .getPackageArchiveInfo(apk.getAbsolutePath(), packageFlags());
                if (archive == null || !PACKAGE_NAME.equals(archive.packageName) ||
                        archive.versionCode != update.versionCode)
                    throw new SecurityException("APK package/version is not our expected N500 Preview");
                if (!update.signerSha256.equalsIgnoreCase(signer(archive)))
                    throw new SecurityException("Downloaded APK signing certificate is unexpected");

            } catch (Exception e) {
                problem = e.getMessage();
                if (apk.exists()) apk.delete();
            }
            final String issue = problem;
            UI.post(() -> {
                if (activity.isDestroyed() || activity.isFinishing()) return;
                if (issue != null) {
                    if (issue.startsWith("SIGNING_KEY_CHANGED:")) {
                        new AlertDialog.Builder(activity,
                                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("Safe update needs migration")
                                .setMessage(issue.substring("SIGNING_KEY_CHANGED:".length())
                                    + "\n\nYour stable Glass TV v1.0.0 is untouched. "
                                    + "If you choose a manual upgrade, uninstall ONLY "
                                    + "com.n500.glasstvhome.preview and install the new Preview. "
                                    + "Its experimental settings will be lost.")
                                .setPositiveButton("View GitHub steps", (d,w) ->
                                        openRelease(activity, update.webUrl))
                                .setNegativeButton("Cancel", null).show();
                    } else {
                        alert(activity, "Update safely stopped", issue);
                    }
                } else {
                    confirmInstaller(activity, apk);
                }
            });
        });
    }

    private static void confirmInstaller(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= 26 &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Allow APK installation")
                .setMessage("Android requires your approval before this launcher can open an APK installer. "
                        + "Enable 'Install unknown apps' for Glass TV Preview and run the update again.")
                .setPositiveButton("Open Android Settings", (d,w) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                })
                .setNegativeButton("Cancel", null).show();
            return;
        }
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Verified Glass TV APK")
                .setMessage("The APK's SHA-256, package name, version and certificate all match."
                        + "\n\nOpen Android's installer to approve the update?"
                        + "\n\nYour original v1.0.0 Home is unaffected.")
                .setPositiveButton("Open installer", (d,w) -> {
                    try {
                        Uri file = FileProvider.getUriForFile(activity,
                                activity.getPackageName() + ".fileprovider", apk);
                        Intent install = new Intent(Intent.ACTION_VIEW);
                        install.setDataAndType(file, "application/vnd.android.package-archive");
                        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(install);
                    } catch (Exception e) {
                        alert(activity, "Couldn't open installer", e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }
}
