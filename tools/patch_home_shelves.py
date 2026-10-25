#!/usr/bin/env python3
"""Integrate compact real-data streaming shelves & collapse-on-browse in N500 TV Preview.

Runs AFTER existing base + preview + mini-weather + phone-link patches.
Does not touch stable Home or add new network calls/fictional content.
"""
from pathlib import Path
import shutil
import sys


def once(path: Path, old: str, new: str):
    text=path.read_text(encoding="utf-8")
    if text.count(old)!=1:
        raise RuntimeError(f"{path}: expected one anchor, got {text.count(old)}: {old[:90]!r}")
    path.write_text(text.replace(old,new,1),encoding="utf-8")


def main():
    if len(sys.argv)!=2: raise SystemExit("Usage: python3 tools/patch_home_shelves.py _upstream/launcher")
    root=Path(sys.argv[1])
    manifest=root/"app/src/main/AndroidManifest.xml"
    if "android.intent.category.HOME" in manifest.read_text(encoding="utf-8"):
        raise RuntimeError("Refusing to modify a HOME-capable stable launcher")
    source=Path(__file__).resolve().parent.parent/"tv-additions"/"N500HomeSections.java"
    dest=root/"app/src/main/java/com/gathin/tvhome/N500HomeSections.java"
    if dest.exists(): raise RuntimeError("Already patched")
    shutil.copyfile(source,dest)

    # Reading TV provider data may be denied unless OS or ADB grants this
    # permission. Never request system, root, or signature privileges.
    once(manifest,'    <uses-permission android:name="android.permission.INTERNET" />',
        '''    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.READ_TV_LISTINGS" />''')
    layout=root/"app/src/main/res/layout/activity_main.xml"
    once(layout,
        '''        <RelativeLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content">''',
        '''        <RelativeLayout
            android:id="@+id/n500Header"
            android:layout_width="match_parent"
            android:layout_height="wrap_content">''')

    main=root/"app/src/main/java/com/gathin/tvhome/MainActivity.java"
    once(main,'    private View mainContent;',
        '    private View mainContent;\\n    private N500HomeSections n500Sections;'.replace("\\n","\n"))
    once(main,'        grid.setAdapter(adapter);',
        '''        grid.setAdapter(adapter);
        // The rich streaming shelf is assembled from actual installed apps,
        // recent launch history and readable Android TV program records.
        n500Sections = N500HomeSections.attach(this, handler, (LinearLayout) mainContent);
        handler.post(() -> {
            if (n500Sections != null) n500Sections.refresh();
        });''')

    once(main,'''        lastFocusedKey = adapter.keyAt(position);''',
        '''        lastFocusedKey = adapter.keyAt(position);
        if (n500Sections != null) n500Sections.onTileFocus(position, COLUMNS);''')

    once(main,'''                updateTopFade();
                if (!glassShown) return;''',
        '''                updateTopFade();
                if (n500Sections != null) n500Sections.onScrolled(rv.computeVerticalScrollOffset());
                if (!glassShown) return;''')

    once(main,'''                if (position <= 1 && weatherAirQualityView != null''',
        '''                if (position <= 1 && n500Sections != null && n500Sections.focusShelf()) {
                    return true;
                }
                if (position <= 1 && weatherAirQualityView != null''')

    once(main,'''        // Row 2: Location API Haze''',
        '''        addInputRow(rows, close, R.drawable.ic_livetv, "Home Layout & Streaming", -1, () -> {
            SoundEffects.playSelect();
            if (n500Sections != null) n500Sections.showSettings();
        });

        // Row 2: Location API Haze''')

    once(main,'    private void showN500PhoneLink() {',
        '''    /** Focus helper for compact streaming tiles. */
    public void n500FocusFirstApp() {
        focusTile(0);
    }

    private void showN500PhoneLink() {''')

    once(main,'        if (n500Remote != null) {\\n            n500Remote.stop();'.replace("\\n","\n"),
        '''        if (n500Sections != null) n500Sections.destroy();
        if (n500Remote != null) {
            n500Remote.stop();''')

    once(main,'        updateWeatherAqi();\\n        WeatherAirQuality.syncIfDue(this, this::updateWeatherAqi);'.replace("\\n","\n"),
        '''        updateWeatherAqi();
        WeatherAirQuality.syncIfDue(this, this::updateWeatherAqi);''') # no-op assertion

    assert 'android.intent.category.HOME' not in manifest.read_text(encoding="utf-8")
    assert 'n500Sections.onTileFocus' in main.read_text(encoding="utf-8")
    print("N500 streaming shelves + smart auto-hide integrated safely into Preview.")


if __name__=="__main__":
    main()
