#!/usr/bin/env python3
"""Patch experimental Phone Link into the SIDE-BY-SIDE NON-HOME Glass Preview.

Never use for production v1.0.0 or any Home-registered APK.
Copies Java sources from tv-additions, adds a manually-enabled server menu,
and registers the explicit MediaProjection approval / foreground service path.
"""
from pathlib import Path
import shutil
import sys


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one instance of {old[:75]!r}; found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 tools/patch_phone_link.py _upstream/launcher")
    tree = Path(sys.argv[1])
    manifest = tree / "app/src/main/AndroidManifest.xml"
    xml = manifest.read_text(encoding="utf-8")
    if "android.intent.category.HOME" in xml or "com.n500.glasstvhome.preview" not in (
            tree / "app/build.gradle").read_text(encoding="utf-8"):
        raise RuntimeError("Phone Link is PREVIEW ONLY and must NOT register as Home")

    root = Path(__file__).resolve().parent.parent
    classes = tree / "app/src/main/java/com/gathin/tvhome"
    for name in ("N500RemoteServer.java", "N500CaptureService.java", "N500PairingStore.java"):
        destination = classes / name
        if destination.exists():
            raise RuntimeError(f"Refusing to overwrite {destination}")
        shutil.copyfile(root / "tv-additions" / name, destination)

    replace_once(manifest,
        '    <uses-permission android:name="android.permission.INTERNET" />',
        '''    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />''')
    replace_once(manifest,
        '''        <provider
            android:name="androidx.core.content.FileProvider"''',
        '''        <service
            android:name=".N500CaptureService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />

        <provider
            android:name="androidx.core.content.FileProvider"''')

    activity = classes / "MainActivity.java"
    replace_once(activity,
        "    private TextView weatherAirQualityView;",
        '''    private TextView weatherAirQualityView;
    private N500RemoteServer n500Remote;
    private AlertDialog n500PairingDialog;
    private static final int N500_SCREEN_CAPTURE_REQUEST = 7513;''')

    replace_once(activity,
        '''        // Row 6: All Settings (Android Settings)''',
        '''        // N500 companion phone service is disabled until user explicitly opens this menu.
        addInputRow(rows, close, R.drawable.ic_wifi, "Phone Link (Experimental)", -1, () -> {
            SoundEffects.playSelect();
            showN500PhoneLink();
        });

        // Row 6: All Settings (Android Settings)''')

    replace_once(activity,
        "    private void showSoundEffectsDialog() {",
        '''    /** Restore Preview's focus once the phone has successfully paired. */
    public void n500PairingCompleted() {
        if (n500PairingDialog != null) {
            n500PairingDialog.dismiss();
            n500PairingDialog = null;
        }
        Toast.makeText(this, "Phone paired. Remote controls ready in Preview.",
                Toast.LENGTH_SHORT).show();
    }

    private void n500RestoreRememberedPhone() {
        // AndroidKeyStore may be slow on projector hardware; never block UI.
        // Auto-start occurs only after the owner paired once.
        new Thread(() -> {
            if (!N500RemoteServer.hasRememberedPhone(getApplicationContext())) return;
            N500RemoteServer candidate = new N500RemoteServer(this, handler);
            if (!candidate.start()) return;
            handler.post(() -> {
                if (isFinishing() || isDestroyed() || n500Remote != null) {
                    candidate.stop();
                } else {
                    n500Remote = candidate;
                }
            });
        }, "n500-pairing-restore").start();
    }

    private void showN500PhoneLink() {
        // Never automatically start the server on an unpaired device.
        if (n500Remote == null) {
            n500Remote = new N500RemoteServer(this, handler);
            if (!n500Remote.start()) {
                Toast.makeText(this, "Could not start Phone Link on port 8765", Toast.LENGTH_LONG).show();
                n500Remote = null;
                return;
            }
        } else if (!n500Remote.isPaired()) {
            n500Remote.rotatePairingCode();
        }
        String pairingHint = n500Remote.isPaired()
                ? "\\nPhone remembered! Open Glass TV Remote on your home Wi-Fi to reconnect automatically."
                + "\\nTo pair another phone, choose Disconnect Phone and reopen Phone Link."
                : "\\nOne-time pairing PIN: " + n500Remote.pairingCode()
                + "\\nExpires after 2 minutes";
        String info = "Phone and N500 must be on the same private Wi-Fi."
                + "\\n\\nAddress: " + n500Remote.address()
                + pairingHint
                + "\\n\\nOpen Glass TV Remote on your phone > Setup > Pair."
                + "\\n\\nDeveloper preview only: unencrypted LAN. Do not use on public Wi-Fi."
                + "\\nAndroid will show a separate confirmation for Live View."
                + "\\nProtected video and HDMI may appear blank.";
        if (n500PairingDialog != null) n500PairingDialog.dismiss();
        n500PairingDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("N500 Phone Link")
                .setMessage(info)
                .setPositiveButton("Close", (d, w) -> d.dismiss())
                .setNeutralButton("Stop Live View", (d, w) -> {
                    Intent stop = new Intent(this, N500CaptureService.class)
                            .setAction(N500CaptureService.ACTION_STOP);
                    startService(stop);
                })
                .setNegativeButton("Disconnect Phone", (d, w) -> {
                    n500Remote.forgetPhone();
                    n500Remote = null;
                }).create();
        n500PairingDialog.setOnDismissListener(d -> n500PairingDialog = null);
        n500PairingDialog.show();
    }

    /** Only called from the manually enabled, paired local phone connection. */
    public void n500RequestLive() {
        if (isFinishing() || !hasWindowFocus() || N500CaptureService.isActive()) return;
        try {
            android.media.projection.MediaProjectionManager manager =
                    (android.media.projection.MediaProjectionManager)
                            getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager != null) {
                startActivityForResult(manager.createScreenCaptureIntent(), N500_SCREEN_CAPTURE_REQUEST);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Live View unsupported: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != N500_SCREEN_CAPTURE_REQUEST) return;
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(this, "Live View was not approved", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent service = new Intent(this, N500CaptureService.class)
                .putExtra(N500CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(N500CaptureService.EXTRA_RESULT_DATA, data);
        try {
            startForegroundService(service);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot start Live View: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showSoundEffectsDialog() {''')

    replace_once(activity,
        '''        // Silent background update check after 5 seconds on launch''',
        '''        n500RestoreRememberedPhone();

        // Silent background update check after 5 seconds on launch''')

    replace_once(activity,
        '''    protected void onDestroy() {
        super.onDestroy();''',
        '''    protected void onDestroy() {
        if (n500PairingDialog != null) n500PairingDialog.dismiss();
        if (n500Remote != null) {
            n500Remote.stop();
            n500Remote = null;
        }
        super.onDestroy();''')

    # Critical safety checks.
    assert 'android.intent.category.HOME' not in manifest.read_text(encoding="utf-8")
    assert 'N500_SCREEN_CAPTURE_REQUEST' in activity.read_text(encoding="utf-8")
    assert 'N500RemoteServer' in activity.read_text(encoding="utf-8")
    print("Phone Link + MediaProjection added to isolated non-HOME TV preview only.")


if __name__ == "__main__":
    main()
