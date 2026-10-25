#!/usr/bin/env python3
"""Install N500 Preview's safe GitHub updater and bump Preview's Android version.

Run only AFTER base and preview patches. Original stable v1.0.0 untouched.
"""
from pathlib import Path
import shutil
import sys

def replace_one(path, old, new):
    text=path.read_text(encoding="utf-8")
    if text.count(old)!=1:
        raise RuntimeError(f"Expected 1 of {old!r} in {path}, found {text.count(old)}")
    path.write_text(text.replace(old,new,1),encoding="utf-8")

def main():
    if len(sys.argv)!=2:
        raise SystemExit("Usage: python3 tools/patch_github_update.py _upstream/launcher")
    tree=Path(sys.argv[1])
    manifest=(tree/"app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    if "android.intent.category.HOME" in manifest:
        raise RuntimeError("Updater must not be added to the known-good stable Home")
    gradle=tree/"app/build.gradle"
    if "applicationId 'com.n500.glasstvhome.preview'" not in gradle.read_text(encoding="utf-8"):
        raise RuntimeError("Only side-by-side N500 Preview may use this updater")
    replace_one(gradle,"versionCode 8","versionCode 11")
    replace_one(gradle,"versionName '2.6.0-n500.preview.1'",
                "versionName '2.6.0-n500.preview.4'")
    updater=tree/"app/src/main/java/com/gathin/tvhome/AppUpdateManager.java"
    existing=updater.read_text(encoding="utf-8")
    if "updates are built in GitHub Actions" not in existing:
        raise RuntimeError("Expected N500's disabled upstream updater, refusing overwrite")
    source=Path(__file__).resolve().parent.parent/"tv-additions"/"AppUpdateManager.java"
    shutil.copyfile(source,updater)
    installed=updater.read_text(encoding="utf-8")
    assert "com.n500.glasstvhome.preview" in installed
    assert "api.github.com/repos/" in installed
    assert "aqmangge/glass-tv-home" not in installed
    assert "certificateDigest" in installed
    assert "DOWNLOAD" not in installed or "download" in installed
    print("N500 Preview OTA: v11, update manifest from OUR GitHub releases, signature+SHA checks.")
if __name__=="__main__":
    main()
