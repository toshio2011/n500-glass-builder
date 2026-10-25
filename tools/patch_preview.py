#!/usr/bin/env python3
"""Derive a deliberately NON-HOME preview APK from the known working N500 build.

Run after patch_glass.py. A preview is launched manually and CANNOT take over
Home through the ordinary Android HOME intent resolver.
"""
from pathlib import Path
import sys

STABLE = "com.n500.glasstvhome"
PREVIEW = "com.n500.glasstvhome.preview"


def one(root: Path, rel: str, old: str, new: str) -> None:
    path = root / rel
    body = path.read_text(encoding="utf-8")
    actual = body.count(old)
    if actual != 1:
        raise ValueError(f"{rel}: expected one instance of {old!r}, got {actual}")
    path.write_text(body.replace(old, new, 1), encoding="utf-8")
    print(f"Patched {rel}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 tools/patch_preview.py _upstream/launcher")
    root = Path(sys.argv[1])
    one(root, "app/build.gradle",
        "applicationId 'com.n500.glasstvhome'",
        "applicationId 'com.n500.glasstvhome.preview'")
    one(root, "app/build.gradle",
        "versionName '2.6.0-n500.1'",
        "versionName '2.6.0-n500.preview.1'")
    one(root, "app/src/main/res/values/strings.xml",
        '<string name="app_name">Glass TV N500</string>',
        '<string name="app_name">Glass TV N500 Preview</string>')
    one(root, "app/src/main/java/com/gathin/tvhome/MainActivity.java",
        '"com.n500.glasstvhome/com.gathin.tvhome.ClockDream"',
        '"com.n500.glasstvhome.preview/com.gathin.tvhome.ClockDream"')
    one(root, "app/src/main/AndroidManifest.xml",
        'android:name="com.n500.glasstvhome.SET_LOCATION"',
        'android:name="com.n500.glasstvhome.preview.SET_LOCATION"')
    one(root, "app/src/main/java/com/gathin/tvhome/LocationBroadcastReceiver.java",
        '"com.n500.glasstvhome.SET_LOCATION"',
        '"com.n500.glasstvhome.preview.SET_LOCATION"')

    # Remove ONLY the primary HOME intent-filter; leave LEANBACK_LAUNCHER so
    # preview can be started from the installed-app list or explicit ADB.
    # It is never eligible to replace the known-good home by accident.
    manifest = root / "app/src/main/AndroidManifest.xml"
    source = manifest.read_text(encoding="utf-8")
    home_filter = """            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
"""
    if source.count(home_filter) != 1:
        raise ValueError("Expected one exact main HOME intent filter; refusing preview build")
    source = source.replace(home_filter, "", 1)
    if 'android.intent.category.HOME' in source:
        raise ValueError("Preview must not expose any Android HOME intent")
    manifest.write_text(source, encoding="utf-8")

    gradle = (root / "app/build.gradle").read_text(encoding="utf-8")
    assert "applicationId 'com.n500.glasstvhome.preview'" in gradle
    assert "namespace 'com.gathin.tvhome'" in gradle
    print("Verified unique preview package, preserved namespace, NO HOME intent.")


if __name__ == "__main__":
    main()
