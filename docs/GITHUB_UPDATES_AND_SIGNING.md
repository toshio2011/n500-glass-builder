# GitHub updates and APK signing — N500

## What we built

The experimental projector Preview has an in-app Settings > Check for Updates implementation starting with v0.3:

https://github.com/toshio2011/n500-glass-builder/releases/tag/n500-v0.3-github-updater-preview

The updater checks ONLY our public GitHub Releases preview channel. It accepts only releases with a n500-PROJECTOR-update.json manifest, a strictly higher versionCode and a matching APK asset. It compares the APK SHA-256 against GitHub's release digest, confirms the package name is com.n500.glasstvhome.preview, checks the APK version and signing certificate, and then offers Android's normal installer confirmation. It never silently installs, uninstalls or changes your Home launcher.

The original stable package com.n500.glasstvhome v1.0.0 and both original fallback launchers remain untouched.

## First upgrade from v0.2 to v0.3

The existing v0.2 and new v0.3 APKs use different temporary CI debug signing keys. Android will reject in-place upgrades across those keys.

Download the v0.3 projector APK from the release URL above and install using Termux Wi-Fi ADB.

    adb install -r n500-glass-tv-PROJECTOR-preview-v0.3.apk

If INSTALL_FAILED_UPDATE_INCOMPATIBLE occurs, uninstall ONLY experimental Preview:

    adb uninstall com.n500.glasstvhome.preview
    adb install n500-glass-tv-PROJECTOR-preview-v0.3.apk

Uninstalling Preview erases its Preview settings/phone pairing. NEVER uninstall the known-good stable com.n500.glasstvhome package.

## One-time signing setup REQUIRED for future truly one-tap updates

Android requires all in-place updates to use the same signing certificate. Earlier CI builds generated temporary certificates. There is no safe updater workaround for that restriction.

The experimental build workflow now supports a persistent signing keystore through TWO encrypted GitHub Actions repository secrets:

- N500_PREVIEW_KEYSTORE_BASE64 — base64 encoding of a private PKCS12 keystore containing one signing key with alias n500-preview.
- N500_PREVIEW_STORE_PASSWORD — password of that private keystore/key.

These secrets are NOT currently configured by our code changes. A repository owner must add them privately. If both are present, CI signs every future Preview build with the same key. If neither is set, builds still compile for testing with temporary debug signatures (not suitable for seamless upgrades). If only one secret is set, builds fail for safety.

On your own phone in Termux, you may create a keystore locally:

    pkg install -y openjdk-17 coreutils
    mkdir -p ~/n500-private-keys
    chmod 700 ~/n500-private-keys
    keytool -genkeypair -storetype PKCS12 -keystore ~/n500-private-keys/n500-preview.p12 -alias n500-preview -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=N500 Glass TV Preview"
    base64 -w0 ~/n500-private-keys/n500-preview.p12 > ~/n500-private-keys/keystore-base64.txt

Choose a strong private password at the local keytool prompt. Use GitHub repository Settings > Secrets and variables > Actions to add the two secrets. The base64 text IS the private key material, NOT encryption: never paste it into chat, public GitHub files, issues, screenshots, or release assets. Keep a secure offline backup of the original .p12 key; delete the temporary base64 text copy after upload.

When the persistent signing key is first adopted, Android will require one FINAL manual reinstall of the experimental Preview signed by that key. Every later Preview release using the SAME key and an incremented versionCode can then install in-place using the GitHub Check for Updates button.

## Stable channel remains separate

Original v1.0.0 (known-good Home, signed with an old debug key): https://github.com/toshio2011/n500-glass-builder/releases/tag/n500-v1.0.0-known-good

Do not redirect stable Home to experimental prereleases. A safe user-approved stable migration must be prepared separately. The phone Android remote APK also has a different package name and needs its own update channel; the projector button does not update it.

## Acceptance checks

- GitHub Actions Android Preview v0.3 build: successful.
- GitHub release APK/checksum/certificate manifest: published.
- Device tests pending: GitHub update discovery, version comparison, mismatch warning, signed APK verification, Package Installer approval, Home survival after reboot.
- Future compatibility test pending: configure private permanent signing secrets, release another compatible APK and demonstrate seamless in-place upgrade.
