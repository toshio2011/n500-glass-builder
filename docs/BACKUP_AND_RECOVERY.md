# N500 Glass TV — backup and recovery from Android phone (Termux)

**Known-good backup:** [Glass TV v1.0.0 GitHub Release](https://github.com/toshio2011/n500-glass-builder/releases/tag/n500-v1.0.0-known-good) (published 25 October 2026). This release stores the **exact original APK** that was tested on the N500, not a fresh build.

## What is backed up?

- ✅ Exact known-good `com.n500.glasstvhome` APK (release asset and SHA256).
- ✅ Source builder at the original pinned Glass TV 2.6.0 commit.
- ✅ Emergency recovery using the existing CleanYourTV and Google TV launchers (both remain installed).
- ❌ Not a copy of the Android projector firmware, vendor partitions, Wi-Fi password, app-private data, Glass TV settings, or external apps.
- ❌ Not a guarantee that any incompatible signing-key build can be installed over v1.0.0 without uninstalling.

The exact original APK's SHA256 is:

```
7fdf5785a394301d96483c0983a6f30b2a4b4aec50713f9ae6dc5d8c10c3a2e2
```

## Open the phone recovery menu

On **your Android phone**, in Termux, with phone and N500 on the same Wi-Fi:

```sh
pkg install -y curl coreutils android-tools
mkdir -p "$HOME/n500-glass"
curl -fL --retry 2 \
  https://raw.githubusercontent.com/toshio2011/n500-glass-builder/main/tools/n500-recovery.sh \
  -o "$HOME/n500-glass/n500-recovery.sh"
adb connect 192.168.100.243:5555
bash "$HOME/n500-glass/n500-recovery.sh"
```

**IP note:** `192.168.100.243` is the IP that worked previously. If the N500's Wi-Fi IP changed after reboot, read the new IP from N500 Network settings and substitute it. You can also select a specific ADB target with `N500_ADB_SERIAL=IP:5555 bash ...` if multiple Android devices are connected.

The menu presents:
1. **Status** — inspect currently installed launchers and focused app (no changes).
2. **Download backup** — download v1.0.0 and verify SHA-256. No change to projector.
3. **Rollback to v1.0.0** — confirm before changes, download/verify first, rescue original launchers, then attempt install.
4. **Emergency rescue** — re-enable original CleanYourTV and Google TV launchers if your Glass Home is unusable.
5. Exit.

### Safest thing to do now

Choose **2** to cache the known-good APK on your phone and **1** to check status. You do *not* need to run rollback while the projector is working.

## What "Rollback to v1.0.0" actually does

Only after you deliberately type `ROLLBACK`:
1. Confirm original APK has been downloaded and SHA-256 validated.
2. Re-enable **CleanYourTV** (`com.gathin.tvhome`) and **Google TV** (`com.google.android.tvlauncher`) so another usable Home exists.
3. Try `adb install -r -d` with the verified v1.0.0 APK, **without** uninstalling first. Android may reject this on non-debuggable packages or when signatures differ.
4. If direct install fails, prompt for a second, explicit `UNINSTALL` confirmation. Only `com.n500.glasstvhome` is uninstalled, and its private settings will be erased; **the projector firmware and other apps remain unchanged**.
5. Install v1.0.0 and launch Glass TV before changing competing Home settings.
6. On the specific N500 tested, disable both competing launchers for user 0 and re-select Glass TV Home. Check the focused app.
7. If launcher/Home verification fails while connected, re-enable fallback launchers.

**After rollback:** independently confirm physical Home button, installed app launch, projector Settings and normal reboot. Script verification is *not* a substitute for checking the visible projected image.

### Immediate emergency rescue without downloading anything

When ADB is connected and authorized:

```sh
adb shell pm enable --user 0 com.gathin.tvhome
adb shell pm enable --user 0 com.google.android.tvlauncher
adb shell am start -n com.gathin.tvhome/.MainActivity
```

These commands are reversible, do not uninstall either launcher, and were chosen based on your N500 packages. ADB must be connected; a lost Wi-Fi network connection cannot be recovered by this script alone.

## Before future feature releases

1. Existing v1.0.0 installed APK was signed with a temporary CI debug certificate. **Do not assume newly built APK signatures match.**
2. Preserve v1.0.0 as stable Home while developing feature builds under a **different Android app ID** such as `com.n500.glasstvhome.preview`. Do not make preview the default launcher.
3. When the feature build is stable, choose a persistent signing certificate for future stable releases and publish a user-approved migration. If signatures cannot match, an uninstall/reinstall is required *once*, and existing launcher-private settings cannot automatically be promised to survive.
4. Never auto-uninstall old builds, disable both fallback launchers, or silently change Home during CI/building.
5. Keep this backup APK on GitHub Releases and in Termux phone cache. **Do not remove the fallback apps.**

**This recovery tool cannot back up private app settings on a non-rooted projector**. The controls and menus described in the master roadmap are not already implemented by the backup.
