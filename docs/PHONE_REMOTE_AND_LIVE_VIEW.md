# N500 Glass TV Remote — Android Phone App with Integrated Live View (experimental)

Branch: [feature/android-phone-remote-live-view](https://github.com/toshio2011/n500-glass-builder/tree/feature/android-phone-remote-live-view)

**This is TWO cooperating APKs, NOT a phone-only trick:**
1. Native **Android phone APK**: package \`com.n500.remote\`, labels **Remote / Live View / Setup**.
2. **N500 TV Preview APK**: package \`com.n500.glasstvhome.preview\` with opt-in **Settings > Phone Link (Experimental)**.

**Your production N500 Glass TV v1.0.0 remains installed, unchanged and the default Home.** The TV preview APK has no \`HOME\` intent filter, and neither build modifies or disables any installed projector launcher.

## Latest build artifacts

- [Phone APK builds](https://github.com/toshio2011/n500-glass-builder/actions/workflows/build-n500-phone.yml) — download **N500-Glass-TV-Remote-PHONE-APK**, unzip \`n500-glass-tv-remote-phone.apk\` to your Android phone.
- [N500 paired TV Preview builds](https://github.com/toshio2011/n500-glass-builder/actions/workflows/build-n500-preview.yml) — select a **green run from the same feature branch**, download \`N500-Glass-TV-PREVIEW-APK\` and install the contained \`n500-glass-tv-preview.apk\` on the projector using Termux/Wi-Fi ADB.
- [Stable v1.0.0 rollback release](https://github.com/toshio2011/n500-glass-builder/releases/tag/n500-v1.0.0-known-good).

These are **development APKs signed using CI debug keys that may change across runs**. If an existing preview or phone developer APK fails to update with \`INSTALL_FAILED_UPDATE_INCOMPATIBLE\`, remove **only the experimental package**, not the stable \`com.n500.glasstvhome\` launcher. Uninstall erases that experimental app's own settings.

## Install the two APKs

### TV Preview via phone Termux

Keep phone and N500 on same Wi-Fi. Obtain projector's current IP from Settings (previously \`192.168.100.243\`). For downloaded/unzipped TV preview APK:

\`\`\`sh
adb connect 192.168.100.243:5555
adb install /path/to/n500-glass-tv-preview.apk
adb shell am start -n com.n500.glasstvhome.preview/com.gathin.tvhome.MainActivity
\`\`\`

**DO NOT** run \`pm disable-user\` / \`set-home-activity\` / \`adb uninstall com.n500.glasstvhome\` for this experiment. The stable Glass v1.0.0 is still your physical Home button target.

### Phone

Download and open \`n500-glass-tv-remote-phone.apk\` on your **Android phone**. You may need to allow installation from your browser/Files app.

On the N500 **Preview** using the projector remote:
1. Open **⚙ Settings > Phone Link (Experimental)**.
2. The TV shows its private LAN \`IP:8765\` and a **six-digit code**, valid for two minutes.
3. In phone app go **Setup → Enter projector IP and pairing code**.
4. Pair. Pairing tokens last only for the current TV preview service session, and are not automatically saved across phone launches. Re-open Phone Link on TV to generate another code.

### Test the remote

In the phone's **Remote** tab, D-pad and OK are injected **into the foreground TV Preview Activity only**. These actions are not yet a system-wide remote control for Netflix/HDMI apps. **HOME** opens the system-selected Home launcher (your original stable Glass TV); volume adjustment depends on firmware/audio routing; **Back** is scoped to the preview.

Expected: some controls may return \`409 Open Glass TV Preview\` when another Android app is in front. This is an intentional, honest limitation of a normal third-party Android launcher without privileged input injection. No root, accessibility takeover, or insecure debugging bridge is enabled.

### Test *actual* Live View

1. Reopen the TV **Preview** so it's in the foreground.
2. In the phone app **Live View**, choose **Start Live View**.
3. **On the N500**, Android presents a screen-sharing consent dialog. Use the **physical projector remote** to approve it.
4. If the N500 firmware supports screen capture and the MediaProjection service starts, the phone displays **actual JPEG frames** from Android (currently 640 px wide / approximately one frame per second). This is a genuine experimental live feed, not a simulated wallpaper.
5. Press **STOP** on your phone or use **Stop** from the N500 capture notification / TV Phone Link. The phone stops sharing on app background/disconnect, and Android can revoke permission.
6. Expect DRM protected video areas to be blank. **HDMI1/HDMI2 capture is not verified and may not be possible** on N500 firmware. Audio is NOT yet implemented; do not claim otherwise.

**This is an untested real-device experiment.** GitHub compiling both APKs is not proof that the N500 firmware permits MediaProjection or that the phone can receive video.

## Safety/security boundaries

- Feature **OFF until manually enabled** from TV Preview Settings; Live View requests explicit **Android consent every session**.
- Private **RFC1918 Wi-Fi** only, port \`8765\`; don't use on shared/public Wi-Fi, don't forward/expose the port to the Internet.
- **Transport is HTTP without TLS for this DEVELOPMENT prototype.** The 6-digit PIN and bearer token may be intercepted by another device on that network. Stable release requires encrypted transport and a stronger pairing protocol (ideally certificate-pinned HTTPS).
- Token: 256-bit, random, ephemeral, revoked on restart/TV-side disconnect; pairing PIN: short-lived 2 minutes. The TV does not accept unauthenticated keys or video reads.
- MediaProjection is not a DRM bypass; protected surfaces should remain hidden, and HDMI may render outside the Android compositor.
- The TV capture process uses a visible foreground notification with an independent Stop action; if Android revokes capture it releases the virtual display.
- Streaming is low FPS and no audio to limit CPU. Don't assume video playback smoothness; it's an interactive diagnostic preview first.

## Acceptance checklist (owner test required)

- [ ] Phone APK installs and UI opens on Android 16 device.
- [ ] TV feature APK installs side by side; default Home remains v1.0.0 after physical Home press and reboot.
- [ ] Phone Link menu displays address/code without lag/crash.
- [ ] Pairing works with correct code, rejects bad/expired code.
- [ ] Remote D-pad changes focus in TV Preview without hijacking physical remote.
- [ ] Start Live View brings up genuine Android permission prompt **on TV**; denying sharing produces no images.
- [ ] Approving sharing produces fresh, readable frames on phone, including when projector navigates among non-protected Android apps.
- [ ] Stop on phone, TV notification, disconnect or OS revoke terminates frames.
- [ ] Netflix/other protected regions remain blank; HDMI unknown.
- [ ] No persistent ADB requirement after installation. N500 v1.0.0 and emergency fallback launchers remain intact.
- [ ] CPU/RAM/performance and overnight sleep/wake remain stable.

If any preview crashes: press **physical Home** to return to stable Glass TV v1.0.0. Remove only preview: \`adb uninstall com.n500.glasstvhome.preview\`. The stable known-good APK remains available in GitHub Releases.


## Design and Home-shelf upgrade (25 October 2026)

**Both APKs have been rebuilt as developer previews; real N500/device UI testing is still required.**

**Phone remote UI (v0.1.0-dev, premium glass refresh):**
- New dark gradient theme, compact connected-state banner, rounded bottom navigation.
- **Remote:** custom drawn touch-responsive circular D-pad, Home/Back/Input and volume.
- **Live View:** larger dark cinematic preview with visible live status, permission and stop controls in the SAME app.
- **Apps:** reads actual launchable apps from the paired projector over an authenticated endpoint; tap to request launch. It does **not** fake Netflix history or control arbitrary app windows afterward.
- **Setup:** simplified IP/code pairing, connection check and forget device.
- Check and update the phone APK using the latest successful [Phone APK workflow](https://github.com/toshio2011/n500-glass-builder/actions/workflows/build-n500-phone.yml) **on this feature branch**.

**Projector UI (side-by-side non-HOME Preview):**
- **Settings → Home Layout & Streaming:** auto-hide header/weather when navigating deeper into app rows; toggle genuine Watch Next, recommended, recently opened, and detected streaming apps; optional expanded row density.
- Defaults to a maximum of **two compact horizontal shelves** to prevent pushing app tiles off screen. Browsing deeper collapses the entire header and shelves to reclaim space; focus returning to the dock expands them.
- Continue Watching and Recommended query Android TV provider; **empty or access-denied results are hidden**. No fabricated recommendations. Recently Opened uses Glass TV launcher history, not playback progress.
- **Settings → Weather Layout** retains Original/Mini/Both/Off and 3/5/7-day controls.
- Check and update the TV Preview only using the latest successful [TV Preview workflow](https://github.com/toshio2011/n500-glass-builder/actions/workflows/build-n500-preview.yml) **on this feature branch**.

The two build workflows publish APK-containing ZIP artifacts. Developer-debug signing can differ from previous preview builds; Android may reject \`adb install -r\`. In that case, uninstall ONLY \`com.n500.glasstvhome.preview\` on the TV; never uninstall \`com.n500.glasstvhome\` (working stable). On the phone you may need to remove ONLY \`com.n500.remote\` when a development signature changes, then reinstall the new phone APK and pair again.

**Device acceptance remains open:** projector overlay fit, mini-weather height, seamless collapse and expand, app shortcuts, physical D-pad, Android TV provider permission/absence, pairing, Live View permission and real captured frames, protected content blanking. Passing GitHub build is not proof these are correct on the real N500.

## Next

- Full pairing integration, encrypted LAN transport, robust reconnect/disconnect.
- Global remote capabilities only where Android permits (explicit permissions and user control).
- Low-latency H.264/WebRTC stream if real-device baseline works, optional audio only with permission.
- Settings editor inside the same phone app, showing weather / streaming / multi-view controls when supported.
