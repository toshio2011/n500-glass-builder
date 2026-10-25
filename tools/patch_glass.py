#!/usr/bin/env python3
"""Make a side-by-side, non-conflicting Glass TV Home APK from pinned upstream source.

This intentionally does NOT disable or remove any N500/projector packages.
The Java namespace stays com.gathin.tvhome so R/imports keep working;
only the installed Android applicationId changes.
"""
from __future__ import annotations

import sys
from pathlib import Path

OLD_ID = "com.gathin.tvhome"
NEW_ID = "com.n500.glasstvhome"


def replace_once(root: Path, rel: str, old: str, new: str) -> None:
    path = root / rel
    source = path.read_text(encoding="utf-8")
    matches = source.count(old)
    if matches != 1:
        raise RuntimeError(f"{rel}: expected exactly one instance of {old!r}, found {matches}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")
    print(f"Patched {rel}: {old!r} -> {new!r}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 tools/patch_glass.py _upstream/launcher")
    root = Path(sys.argv[1]).resolve()
    if not (root / "app/build.gradle").is_file():
        raise SystemExit(f"Not a Glass TV launcher source tree: {root}")

    # A new Android app ID prevents UPDATE_INCOMPATIBLE with the original CleanYourTV.
    # Do NOT rename the Java namespace: that would break upstream's generated R class.
    replace_once(
        root, "app/build.gradle",
        "applicationId 'com.gathin.tvhome'",
        "applicationId 'com.n500.glasstvhome'",
    )
    replace_once(
        root, "app/build.gradle",
        "versionName '2.6.0'",
        "versionName '2.6.0-n500.1'",
    )
    replace_once(
        root, "app/src/main/res/values/strings.xml",
        '<string name="app_name">Home</string>',
        '<string name="app_name">Glass TV N500</string>',
    )

    # Correct explicit component string while retaining the original Java class namespace.
    replace_once(
        root, "app/src/main/java/com/gathin/tvhome/MainActivity.java",
        '"com.gathin.tvhome/.ClockDream"',
        '"com.n500.glasstvhome/com.gathin.tvhome.ClockDream"',
    )

    # Avoid the original launcher and the N500 variant responding to the same broadcast.
    replace_once(
        root, "app/src/main/AndroidManifest.xml",
        'android:name="com.gathin.tvhome.SET_LOCATION"',
        'android:name="com.n500.glasstvhome.SET_LOCATION"',
    )
    replace_once(
        root, "app/src/main/java/com/gathin/tvhome/LocationBroadcastReceiver.java",
        'public static final String ACTION_SET_LOCATION = "com.gathin.tvhome.SET_LOCATION";',
        'public static final String ACTION_SET_LOCATION = "com.n500.glasstvhome.SET_LOCATION";',
    )

    # Upstream OTA points to an APK with the OLD conflicting app ID. Disable it to
    # prevent accidentally triggering an incompatible/unsafe "update" later.
    updater = root / "app/src/main/java/com/gathin/tvhome/AppUpdateManager.java"
    source = updater.read_text(encoding="utf-8")
    if "class AppUpdateManager" not in source or "checkForUpdates(" not in source:
        raise RuntimeError("Unexpected upstream AppUpdateManager: refusing to replace.")
    updater.write_text(
        """package com.gathin.tvhome;

import android.app.Activity;
import android.widget.Toast;

/**
 * N500 side-by-side build: upstream OTA downloads target a DIFFERENT app ID.
 * This variant must be updated from its own GitHub Actions artifact, not
 * from the original app's in-app updater.
 */
public final class AppUpdateManager {
    private AppUpdateManager() {}

    public interface CheckCallback {
        void onResult(boolean hasUpdate, String versionName, String changelog, String apkUrl);
    }

    public static void checkForUpdates(Activity activity, boolean manualCheck) {
        if (manualCheck && activity != null && !activity.isFinishing()) {
            Toast.makeText(activity,
                "Glass TV N500: updates are built in GitHub Actions",
                Toast.LENGTH_LONG).show();
        }
    }
}
""",
        encoding="utf-8",
    )
    print("Disabled incompatible upstream APK auto-updater.")

    # Safety assertions: no changes to namespace, or original source package paths.
    gradle = (root / "app/build.gradle").read_text(encoding="utf-8")
    manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    assert "namespace 'com.gathin.tvhome'" in gradle
    assert "applicationId 'com.n500.glasstvhome'" in gradle
    assert 'android.intent.category.HOME' in manifest
    assert 'android:authorities="${applicationId}.fileprovider"' in manifest
    print("Verified distinct package ID and HOME intent; ready to build.")


if __name__ == "__main__":
    main()
