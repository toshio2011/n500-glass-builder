# N500 Favorites-first Home redesign (Preview v0.4)

Implemented in experimental non-HOME package com.n500.glasstvhome.preview only, preserving the known-good stable v1.0.0.

## Layout and behavior

- Existing **top header** retains clock, compact weather, Wi-Fi, Inputs and Settings.
- **Favorites** comes first, is horizontally scrollable and has NO fixed top-five limit.
- On first install, Favorites seeds from actual installed packages in Glass TV's existing configured dock (where available). Later favorites are saved in a dedicated Preview preference.
- **Add apps** opens a separate, TV-remote-navigable **All Apps** page, enumerating real installed apps. Selecting one offers Add to Favorites / Remove from Favorites / Open app / Move left / Move right (the latter two for favorites).
- Long-press a favorite tile to open the management menu. Order is persisted.
- **Continue Watching** follows Favorites, using genuine Android TV Watch Next titles, launch intents and thumbnail/poster URI if available.
- **Recommended** is another actual Android TV preview program strip, again using real artwork when accessible.
- **Streaming** displays installed streaming app icons (as shortcuts, NOT fabricated recommended shows).
- **All Apps** exists separately instead of duplicating a huge underlying app grid onto Home.
- The previously overlapping hero/prayer info and floating app-grid glider are hidden in this experimental Preview hub; the regular upstream grid remains in the source but is not drawn. This avoids the serious wallpaper/prayer/content overlap reported in N500 photos.
- The hub lives inside its own vertical ScrollView below the existing header, with horizontally scrolling cards inside. It lets the user browse more rows without the cards colliding or pushing the header.
- Media entries are never invented when Android TV denies content-provider access; missing artwork falls back to an understated gradient card with actual title, not a fake thumbnail.

## Device tests still needed

- Verify initial seeded favorites reflect the actual N500 dock; manually add apps if not.
- Navigate via physical projector D-pad and paired phone D-pad; check all controls and back navigation.
- Scroll vertically through content; ensure no overlap with header or any wallpaper/Islamic widgets.
- Confirm genuine provider thumbnails load when apps publish accessible artwork; some may be missing.
- Test pin, unpin, move left/right, persistence after app reopen, and unlimited horizontal favorites scroll.
- Test All Apps and launching installed streaming apps.
- Test Phone Link pairing/Live View, weather, Settings > Check for Updates, and stability/memory.
- Stable v1.0.0/Home button MUST remain unaffected.

## Known experimental restrictions

- App playback progress can be shown only if Android TV publishes it via an accessible provider. Some streaming apps do not.
- Real CDN poster artwork may fail to load due to DRM, permission or connectivity. Missing posters are not synthesized.
- Phone remote controls still target the foreground Preview. Streaming applications are launched through Android intents and are not subsequently controlled system-wide by this local server.
- GitHub Actions test builds use transient debug signing unless the repository owner configures the protected permanent keystore. A one-time Preview-only uninstall/reinstall may still be needed when switching signing certificates. NEVER uninstall the stable com.n500.glasstvhome Home.
