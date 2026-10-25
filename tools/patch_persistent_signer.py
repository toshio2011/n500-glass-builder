#!/usr/bin/env python3
"""Enable a persistent private signing identity for future N500 PREVIEW APKs.

Only run in CI when repository secrets have been configured. Never commit or
print the keystore/password. First use requires a manual one-time migration
from the old ephemeral-debug-signed preview; subsequent releases signed with
the same key can be installed in place after APK/signature verification.
"""
from pathlib import Path
import os
import sys

def main():
    if len(sys.argv)!=2:
        raise SystemExit("Usage: python3 tools/patch_persistent_signer.py _upstream/launcher")
    path=Path(sys.argv[1])/"app/build.gradle"
    code=path.read_text(encoding="utf-8")
    assert "applicationId 'com.n500.glasstvhome.preview'" in code, "Preview package required"
    store=os.environ.get("N500_PREVIEW_KEYSTORE_PATH", "")
    password=os.environ.get("N500_PREVIEW_STORE_PASSWORD", "")
    if not store or not Path(store).is_file() or not password:
        raise RuntimeError("Missing private signing keystore or its password")
    anchor="    buildTypes {"
    if code.count(anchor)!=1: raise RuntimeError("Unexpected Gradle layout")
    new="""    // CI reads this from protected Actions secrets. Do not commit a keystore!
    signingConfigs {
        n500Persistent {
            storeFile file(System.getenv('N500_PREVIEW_KEYSTORE_PATH'))
            storePassword System.getenv('N500_PREVIEW_STORE_PASSWORD')
            keyAlias 'n500-preview'
            keyPassword System.getenv('N500_PREVIEW_STORE_PASSWORD')
        }
    }
    buildTypes {"""
    code=code.replace(anchor,new,1)
    old="signingConfig signingConfigs.debug"
    if code.count(old)!=1: raise RuntimeError("Expected one debug release signer")
    code=code.replace(old,"signingConfig signingConfigs.n500Persistent",1)
    path.write_text(code,encoding="utf-8")
    print("Enabled persistent, protected GitHub-secret Preview APK signing.")
if __name__=="__main__":
    main()
