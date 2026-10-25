# Test future Glass TV safely with the N500 preview app

The permanent v1.0.0 recovery APK is available at [known-good backup](https://github.com/toshio2011/n500-glass-builder/releases/tag/n500-v1.0.0-known-good).

## Why a separate preview?

A new APK with the same package `com.n500.glasstvhome` would either replace the **working** installed launcher or fail signature verification. Instead, all early experiments use `com.n500.glasstvhome.preview` and can be **installed side by side**.

The preview has a visible label **Glass TV N500 Preview**. Its Android manifest **does not advertise the HOME intent**, so it should not become your default Home app or interfere with the stable physical Home button.

## Build and try a preview

1. Go to [Preview workflow](https://github.com/toshio2011/n500-glass-builder/actions/workflows/build-n500-preview.yml). Run workflow if no green build exists.
2. Download the **N500-Glass-TV-PREVIEW-APK** build artifact and extract `n500-glass-tv-preview.apk` from the ZIP to phone storage, or use `gh run download RUN_ID -R toshio2011/n500-glass-builder -n N500-Glass-TV-PREVIEW-APK -D ~/n500-glass/preview` from Termux.
3. From Termux (same Wi-Fi), connect to N500: `adb connect 192.168.100.243:5555` (substitute current IP).
4. Install the preview only after confirming the artifact's SHA256:

```sh
cd "$HOME/n500-glass/preview"
sha256sum -c n500-glass-tv-preview.sha256
adb install n500-glass-tv-preview.apk
adb shell am start -n com.n500.glasstvhome.preview/com.gathin.tvhome.MainActivity
```

Press your **physical Home button** to return to v1.0.0 stable Glass TV. Do not attempt to make preview Home.

Remove only the preview (not stable) if it crashes or behaves badly:

```sh
adb uninstall com.n500.glasstvhome.preview
```

## How features graduate to stable

1. Implement and test in preview (weather and layout modules first).
2. Verify D-pad, app launching, Home, firmware sleep/wake, memory, Wi-Fi and reboot.
3. Retain your exact v1.0.0 backup and emergency recovery tool.
4. Create stable migration/release, with explicit acknowledgment if signatures differ and settings need to be reconfigured.
5. Update stable Glass TV only when you choose to do so.

**Do not** disable/uninstall `com.gathin.tvhome` or `com.google.android.tvlauncher`. They are the emergency fallback launchers, currently disabled for user 0. No preview or CI workflow connects to your projector.
