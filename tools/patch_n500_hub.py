#!/usr/bin/env python3
"""Replace experimental N500 Preview's crowded shelves with a proper Home hub.

Only targets the NON-HOME package, runs after phone link and mini-weather patches.
No changes to stable Glass TV or the original upstream sources/repository.
"""
from pathlib import Path
import shutil
import sys

def once(path,old,new):
    content=path.read_text(encoding="utf-8")
    if content.count(old)!=1:
        raise RuntimeError(f"Ambiguous anchor in {path}: {repr(old[:100])} ({content.count(old)})")
    path.write_text(content.replace(old,new,1),encoding="utf-8")

def main():
    if len(sys.argv)!=2:
        raise SystemExit("Usage: python3 tools/patch_n500_hub.py _upstream/launcher")
    root=Path(sys.argv[1])
    manifest=root/"app/src/main/AndroidManifest.xml"
    gradle=root/"app/build.gradle"
    if "android.intent.category.HOME" in manifest.read_text(encoding="utf-8"):
        raise RuntimeError("Refusing patch: launcher has HOME intent")
    if "applicationId 'com.n500.glasstvhome.preview'" not in gradle.read_text(encoding="utf-8"):
        raise RuntimeError("Refusing patch: package is not our experimental Preview")
    source=Path(__file__).resolve().parent.parent/"tv-additions/N500Hub.java"
    dest=root/"app/src/main/java/com/gathin/tvhome/N500Hub.java"
    if dest.exists():raise RuntimeError("N500Hub was already installed")
    shutil.copy2(source,dest)
    once(manifest,'    <uses-permission android:name="android.permission.INTERNET" />',
        '    <uses-permission android:name="android.permission.INTERNET" />\n'
        '    <uses-permission android:name="android.permission.READ_TV_LISTINGS" />')

    main=root/"app/src/main/java/com/gathin/tvhome/MainActivity.java"
    once(main,'    private View mainContent;',
         '    private View mainContent;\n    private N500Hub n500Hub;')

    # Attach only after the complete original Activity has initialized its
    # existing widget refs, focus/glass machinery and top settings buttons.
    once(main,'''        btnSettings.setOnClickListener(v -> showSettingsPopup());''',
        '''        btnSettings.setOnClickListener(v -> showSettingsPopup());
        // Preview-only Home hub: a single vertically scrollable layout. The
        // original grid, hero and floating glass no longer overlap new rows.
        n500Hub = N500Hub.attach(this, (LinearLayout) mainContent,
                gridWrap, heroShelf, islamicShelf, glass);''')

    # gridWrap is assigned later in upstream onCreate; attach must run AFTER
    # that point and the settings listeners are configured. Move attach from
    # button listener to the end of onCreate to ensure gridWrap initialized.
    once(main,'''        n500Hub = N500Hub.attach(this, (LinearLayout) mainContent,
                gridWrap, heroShelf, islamicShelf, glass);''','')
    once(main,'''        // Silent background update check after 5 seconds on launch''',
        '''        n500Hub = N500Hub.attach(this, (LinearLayout) mainContent,
                gridWrap, heroShelf, islamicShelf, glass);

        // Silent background update check after 5 seconds on launch''')

    # Don't let the upstream grid steal focus when returning from another app.
    once(main,'''    private void restoreFocus() {
        final String returnKey = lastFocusedKey;''',
        '''    private void restoreFocus() {
        if (n500Hub != null) {
            n500Hub.reload();
            n500Hub.restoreFocus();
            tilesReady = true;
            resuming = false;
            return;
        }
        final String returnKey = lastFocusedKey;''')

    # A remote Back key in All Apps must return to Home, not trigger the
    # original wallpaper/fullscreen double-Back behavior.
    once(main,'''    private void handleBackPress() {
        if (openMenu != null) {''',
        '''    private void handleBackPress() {
        if (n500Hub != null && n500Hub.backHome()) return;
        if (openMenu != null) {''')

    once(main,'''        if (movingPos < 0) {
            if (code == KeyEvent.KEYCODE_BACK''',
        '''        if (movingPos < 0) {
            if (n500Hub != null && n500Hub.isAppsPage()
                    && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
                if (event.getAction() == KeyEvent.ACTION_UP) n500Hub.backHome();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK''')

    # Preserve normal settings but expose All Apps and favorites management.
    once(main,'''        // Row 2: Location API Haze''',
        '''        addInputRow(rows, close, R.drawable.ic_livetv, "All Apps & Favorites", -1, () -> {
            SoundEffects.playSelect();
            if (n500Hub != null) n500Hub.openApps();
        });

        // Row 2: Location API Haze''')

    # Navigation from the visible top bar should use visible favorites, not
    # hidden RecyclerView tiles.
    once(main,'''                focusTile(lastFocused % COLUMNS);
                return true;''',
        '''                if (n500Hub != null) n500Hub.restoreFocus();
                else focusTile(lastFocused % COLUMNS);
                return true;''')

    # The old tile grid must never draw an attention prompt over this Home.
    once(main,'''    private void showCardPrompt() {''',
        '''    private void showCardPrompt() {
        if (n500Hub != null) return;''')

    # the hub only owns its worker; original lifecycle cleanups stay in place
    once(main,'''        if (n500Remote != null) {
            n500Remote.stop();''',
        '''        if (n500Hub != null) n500Hub.destroy();
        if (n500Remote != null) {
            n500Remote.stop();''')

    content=main.read_text(encoding="utf-8")
    assert "n500Hub = N500Hub.attach(" in content
    assert "n500Hub.restoreFocus()" in content
    assert "All Apps & Favorites" in content
    assert "android.intent.category.HOME" not in manifest.read_text(encoding="utf-8")
    print("Clean Favorites -> Continue -> Recommended -> Streaming N500 Preview hub integrated.")
if __name__=="__main__":
    main()
