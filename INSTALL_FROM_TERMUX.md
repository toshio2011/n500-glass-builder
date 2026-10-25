# Install Glass TV N500 from Termux — phone only

**Build status:** [First successful APK build](https://github.com/toshio2011/n500-glass-builder/actions/runs/40153120439) (2026-10-25).
[Download artifact from GitHub](https://github.com/toshio2011/n500-glass-builder/actions/runs/40153120439/artifacts/12583612273). GitHub may ask you to sign in. It is a ZIP containing `n500-glass-tv.apk` and its checksum.

### Option A — download the APK directly to Termux using GitHub CLI (no phone file permission needed)

In Termux on your Android phone:

```bash
pkg update -y
pkg install -y gh android-tools
gh auth login
mkdir -p "$HOME/n500-glass"
gh run download 40153120439 -R toshio2011/n500-glass-builder -n N500-Glass-TV-APK -D "$HOME/n500-glass"
cd "$HOME/n500-glass"
sha256sum -c n500-glass-tv.sha256
```

Follow `gh auth login` to authorize GitHub in your browser. It is normal for this *new* Termux ADB client to need separate TV authorization from CleanYourTV.

### Option B — use the artifact ZIP downloaded in your phone browser

Sign into GitHub in your phone browser and download the artifact linked above. Extract the ZIP in your Files app. Put `n500-glass-tv.apk` in your phone Downloads. In Termux: `termux-setup-storage`, allow storage, then use `$HOME/storage/downloads/n500-glass-tv.apk` in the install step below.

### Connect your N500 over Wi-Fi

Keep the projector and phone on the same Wi-Fi and enable **Network/ADB debugging** on the N500 if necessary. Get the projector's IP address from its network settings or CleanYourTV. Then run in Termux, replacing `192.168.1.123` with its real IP address:

```bash
adb connect 192.168.1.123:5555
adb devices -l
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell pm list packages -d
```

Save/screenshot the output and keep your CleanYourTV restore point. If the projector shows an **Allow debugging** authorization dialog, approve it. **Do not disable or uninstall CleanYourTV.**

### Install and try Glass TV (without changing your current Home)

Option A from above:

```bash
adb install "$HOME/n500-glass/n500-glass-tv.apk"
adb shell am start -n com.n500.glasstvhome/com.gathin.tvhome.MainActivity
```

Option B (if downloaded manually to phone Downloads):

```bash
adb install "$HOME/storage/downloads/n500-glass-tv.apk"
adb shell am start -n com.n500.glasstvhome/com.gathin.tvhome.MainActivity
```

The new package is `com.n500.glasstvhome`. The second part of the Android component, `com.gathin.tvhome.MainActivity`, is the **original Java class name** and is correct; it is not a package conflict.

Test the remote's D-pad/OK, launching installed apps, Settings, wallpaper and HDMI inputs. Firmware may limit some input functionality.

### Make Glass the default Home *only after testing*

If your N500 has a launcher chooser, use that. Otherwise, in Termux:

```bash
adb shell cmd package set-home-activity com.n500.glasstvhome/com.gathin.tvhome.MainActivity
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Press Home on the projector remote, then restart the projector and check that Glass returns reliably.

**Fallback:** While CleanYourTV launcher is still installed, you may attempt:

```bash
adb shell cmd package set-home-activity com.gathin.tvhome/com.gathin.tvhome.MainActivity
```

If the projector does not support `cmd package set-home-activity`, do not start disabling launchers; record the error. The original projector launcher may have been disabled already, so **do not uninstall CleanYourTV until a working fallback is verified**.

### Important: future upgrades

This APK is signed in the GitHub runner using a temporary debug key. A new build may use a different key and may require uninstalling this N500 Glass app (losing its own settings), unless stable signing is configured. Do not install a subsequent APK over it without checking signing compatibility.

Also: GitHub artifacts expire; this first artifact expires on **24 November 2026**. Until another successful build is available, use the successful run linked above.
