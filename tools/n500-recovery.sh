#!/usr/bin/env bash
# N500 Glass TV: phone-only recovery tool. Run through Termux, no root.
set -Eeuo pipefail

APP_ID="com.n500.glasstvhome"
GLASS_ACTIVITY="com.n500.glasstvhome/com.gathin.tvhome.MainActivity"
CLEAN_ID="com.gathin.tvhome"
GOOGLE_ID="com.google.android.tvlauncher"
CLEAN_ACTIVITY="com.gathin.tvhome/.MainActivity"
APK_NAME="n500-glass-tv-v1.0.0-known-good.apk"
KNOWN_SHA="7fdf5785a394301d96483c0983a6f30b2a4b4aec50713f9ae6dc5d8c10c3a2e2"
URL="https://github.com/toshio2011/n500-glass-builder/releases/download/n500-v1.0.0-known-good/$APK_NAME"
CACHE_DIR="${N500_RECOVERY_DIR:-$HOME/n500-glass/recovery}"
APK_PATH="$CACHE_DIR/$APK_NAME"
ADB_SERIAL="${N500_ADB_SERIAL:-}"
RECOVERY_ARMED=0
ADB_CONNECTED=0

say() { printf '\n%s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
require() { command -v "$1" >/dev/null 2>&1 || die "Missing $1 (in Termux: pkg install $2)"; }

verify_apk() {
  [[ -s "$APK_PATH" ]] || return 1
  (cd "$CACHE_DIR" && printf '%s  %s\n' "$KNOWN_SHA" "$APK_NAME" | sha256sum --check --status)
}

download_apk() {
  require curl curl
  require sha256sum coreutils
  mkdir -p "$CACHE_DIR"
  if verify_apk; then
    say "Known-good APK already cached and SHA-256 verified: $APK_PATH"
    return
  fi
  local temp="$APK_PATH.downloading.$$"
  rm -f "$temp"
  say "Downloading original v1.0.0 APK from the GitHub Release..."
  if ! curl --fail --location --retry 2 --connect-timeout 20 --max-time 180 --silent --show-error --output "$temp" "$URL"; then
    rm -f "$temp"
    die "Download failed. No projector changes were made."
  fi
  if ! (cd "$CACHE_DIR" && printf '%s  %s\n' "$KNOWN_SHA" "$(basename "$temp")" | sha256sum --check --status); then
    rm -f "$temp"
    die "SHA-256 mismatch. Refusing to use APK. No projector changes were made."
  fi
  mv -f "$temp" "$APK_PATH"
  say "Verified original known-good APK: $APK_PATH"
}

select_adb() {
  require adb android-tools
  if [[ -n "$ADB_SERIAL" ]]; then
    if [[ "$ADB_SERIAL" == *:* ]]; then adb connect "$ADB_SERIAL" || true; fi
  else
    local devices count
    devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
    count="$(printf '%s\n' "$devices" | sed '/^$/d' | wc -l | tr -d ' ')"
    [[ "$count" == "1" ]] || die "Found $count authorized ADB devices. Connect N500 or set N500_ADB_SERIAL=192.168.100.243:5555"
    ADB_SERIAL="$devices"
  fi
  adb -s "$ADB_SERIAL" get-state >/dev/null || die "N500 unavailable via ADB ($ADB_SERIAL)."
  ADB_CONNECTED=1
  say "Connected ADB target: $ADB_SERIAL"
}
tv() { adb -s "$ADB_SERIAL" "$@"; }

rescue_fallbacks() {
  say "Re-enabling original fallback launchers..."
  tv shell pm enable --user 0 "$CLEAN_ID" || true
  tv shell pm enable --user 0 "$GOOGLE_ID" || true
  tv shell am start -n "$CLEAN_ACTIVITY" || true
  say "Fallback launchers enabled; CleanYourTV launch requested."
}
on_exit() {
  local code=$?
  if [[ "$RECOVERY_ARMED" == 1 && "$ADB_CONNECTED" == 1 ]]; then
    warn "Rollback Home verification failed; restoring fallback launchers."
    rescue_fallbacks || true
  fi
  exit "$code"
}
trap on_exit EXIT

status() {
  select_adb
  say "Installed launcher packages:"
  for p in "$APP_ID" "$CLEAN_ID" "$GOOGLE_ID"; do
    printf '%s: ' "$p"
    tv shell pm path "$p" | head -n 1 || true
  done
  say "Disabled competing launchers:"
  tv shell pm list packages -d | grep -E 'com[.](n500[.]glasstvhome|gathin[.]tvhome|google[.]android[.]tvlauncher)' || true
  say "Current focus:"
  tv shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true
  say "Glass version:"
  tv shell dumpsys package "$APP_ID" | grep -m 2 -E 'versionCode=|versionName=' || true
  if verify_apk; then say "Backup APK checksum VERIFIED: $APK_PATH"; else say "Backup APK not cached (or checksum mismatch)."; fi
}

confirm() {
  local token="$1" answer
  printf '\nType %s to proceed: ' "$token"
  IFS= read -r answer
  [[ "$answer" == "$token" ]] || die "Cancelled. No further changes."
}

rollback() {
  # Verify the backup BEFORE changing a single projector setting.
  download_apk
  select_adb
  say "Rollback to the exact original v1.0.0 Glass TV APK."
  say "Direct update may fail due to signing or version downgrade, then only Glass TV must be uninstalled."
  say "Uninstalling Glass TV erases ONLY its private settings. Other projector apps remain."
  confirm ROLLBACK
  rescue_fallbacks

  say "Attempting direct install/downgrade to preserve launcher data..."
  if tv install -r -d "$APK_PATH"; then
    say "APK installed directly (existing launcher data retained)."
  else
    warn "Android rejected in-place install (may be different signature/version)."
    say "CleanYourTV + Google TV are enabled, so Home has fallbacks."
    say "Proceeding requires removing only $APP_ID, which erases Glass TV settings."
    confirm UNINSTALL
    tv uninstall "$APP_ID" || die "Uninstall failed; fallback launchers are still enabled."
    tv install "$APK_PATH" || die "Installation failed; fallback launchers remain enabled."
  fi

  # Start Glass before disabling anything else. On this specific N500 device,
  # both competing Home packages had to be disabled after setting Home.
  RECOVERY_ARMED=1
  tv shell am start -n "$GLASS_ACTIVITY" || die "Failed launching Glass TV."
  say "Restoring Glass TV to N500 Home..."
  tv shell pm disable-user --user 0 "$GOOGLE_ID"
  tv shell pm disable-user --user 0 "$CLEAN_ID"
  tv shell cmd package set-home-activity --user 0 "$GLASS_ACTIVITY"
  tv shell input keyevent KEYCODE_HOME
  sleep 3
  local focus
  focus="$(tv shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true)"
  printf '%s\n' "$focus"
  if [[ "$focus" != *"$APP_ID"* ]]; then
    die "Glass TV not focused. Recovery will enable the fallback launchers."
  fi
  RECOVERY_ARMED=0
  say "SUCCESS: v1.0.0 installed and focused as Home."
  say "Verify physical Home button, app launching and reboot manually."
}

menu() {
  cat <<'MENU'
N500 Glass TV — Phone Recovery
  1) Check launcher status (read-only)
  2) Download & verify v1.0.0 backup (safe, no device change)
  3) ROLLBACK to v1.0.0 (confirmation required)
  4) Emergency rescue: re-enable CleanYourTV + Google TV
  5) Exit
MENU
  local choice
  printf 'Choose 1-5: '
  IFS= read -r choice
  case "$choice" in
    1) status ;;
    2) download_apk ;;
    3) rollback ;;
    4) select_adb; confirm RESCUE; rescue_fallbacks ;;
    5) exit 0 ;;
    *) die "Unknown option" ;;
  esac
}

case "${1:-menu}" in
  menu) menu ;;
  status) status ;;
  backup|download) download_apk ;;
  rollback) rollback ;;
  rescue) select_adb; confirm RESCUE; rescue_fallbacks ;;
  *) printf 'Usage: bash %s [menu|status|backup|rollback|rescue]\n' "$0"; exit 2 ;;
esac
