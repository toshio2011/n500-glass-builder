# N500 Mini Forecast — first working prototype (preview only)

Implemented in `tools/patch_mini_weather.py`, applied **only** by `.github/workflows/build-n500-preview.yml`. The stable v1.0.0 package and original Home have **not** been altered.

## How to test on N500

1. Open [N500 preview builds](https://github.com/toshio2011/n500-glass-builder/actions/workflows/build-n500-preview.yml) and choose the **latest green** run.
2. Download the `N500-Glass-TV-PREVIEW-APK` artifact and extract the ZIP; it contains `n500-glass-tv-preview.apk` plus SHA-256 file.
3. With Termux and Wi-Fi ADB connected:

```sh
cd "$HOME/n500-glass/preview"        # adjust to location of extracted files
sha256sum -c n500-glass-tv-preview.sha256
adb install n500-glass-tv-preview.apk
adb shell am start -n com.n500.glasstvhome.preview/com.gathin.tvhome.MainActivity
```

The new variant is **Glass TV N500 Preview**, not the current default `com.n500.glasstvhome`. **Press the physical Home button to return to your original launcher**. The preview does not register as an Android Home candidate.

If `adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` after testing an earlier preview, that is because separate GitHub CI runs have different debug signing keys. Remove only `com.n500.glasstvhome.preview` with `adb uninstall com.n500.glasstvhome.preview` and reinstall the latest preview. Do not uninstall `com.n500.glasstvhome` (stable). Preview settings are erased by uninstalling it.

## Feature configuration

With **Preview** open:
- Navigate to **⚙ Settings → Weather Layout**.
- Choose: **Original weather only** (default), **Mini forecast only**, **Original + mini forecast**, or **Hide all Home weather**.
- Configure **3, 5, or 7 days**.
- Toggle **Rain chance** and **High/low temperatures** independently.
- Press **Done** to close. Settings persist in preview across app restarts and projector reboots.
- Select the original pill or mini forecast row to open the existing full-screen Weather Dashboard.

The forecast strip uses **the same weather cache and Open-Meteo data** already used by Glass TV's detailed forecast. It makes **no additional network calls**. If no valid, current forecast exists, it displays **Forecast unavailable** rather than fictitious sample days. On the preview package's first launch, the weather cache starts empty until its first successful update, since stable and preview have different Android app-private storage.

## Device testing checklist

- [ ] Preview installs without touching stable Glass TV.
- [ ] Original mode looks unchanged.
- [ ] Mini-only, both and off modes behave correctly.
- [ ] 3/5/7 days display, no overflow into top navigation at N500 resolution.
- [ ] Rain and high-low toggles update immediately.
- [ ] D-pad navigates from mini row to apps and to top-right Settings; OK opens weather dashboard.
- [ ] When offline or on first launch, no fabricated forecasts appear.
- [ ] After idle, app return and reboot, selected mode remains.
- [ ] Physical Home button still opens **stable** Glass TV v1.0.0.

**Known gaps:** forecast-row position, card style/size, per-item appearance, phone configuration and custom layout presets are subsequent development milestones. UI build passing CI is not proof of correct behavior on your N500; feedback is required before promoting preview to stable.
