# StrongToast Extended V0.6 — Rainbow Fix, Text Styles, Timer Alert

Target: rooted HyperOS 1 / Android 13, SystemUI LSPosed scope.

## Changes since V0.5

- **RGB fix**: native `TextView` no longer relies on an animated `LinearGradient` local matrix. Installs up to 90 per-segment Android `CharacterStyle` spans (each spans one/multiple Unicode codepoints), draws an animated HSV rainbow via `updateDrawState()` and redraws in place. This avoids replacing the string every frame and is less likely to reset the native marquee. A full-text hue cycle is used if span creation fails.
- **Text styles**: `normal`, `bold`, `italic` via `TextView.setTypeface()` once per native binding. This works for RGB, standard styled status, and timer.
- **Timer expiry**: when the countdown hits 00:00, the same Strong Toast displays `Time over!` in red and shakes for ~1.1s; the phone requests **30 seconds of vibration** via `VibrationEffect`. Original timer native request is extended by 30s to keep the alert visible. `stopToastTimer` cancels the ongoing module-owned vibration and native Strong Toast. Device policy, DND, hardware, SystemUI context, or ROM may reject/suppress vibration.
- Preserves V0.4 native 65-minute watchdog and Notification Panel / Control Center / icon-dismiss restore logic. Preserves original `strongtoast` and its DEX, without overwriting them.

## Install (Codex / Android Studio)

The project keeps package ID `com.s9encryptor.strongtoastextended`, its Xposed entrypoint, and System UI scope. In-place updates require the same signing certificate as the installed APK. The current GitHub workflow creates a fresh debug signing key on each runner, and the previous GitHub signing key was not retained. This APK cannot directly update an earlier GitHub build; use your original matching signing configuration when available.

1. Build an APK from this source; `app/build.gradle` sets versionCode 6 / versionName 0.6 and the Android manifest includes `android.permission.VIBRATE`.
2. Install/update APK; LSPosed scope **only System UI** (`com.android.systemui`); reboot.
3. Extract ZIP on Android into Downloads, then run:

```sh
bash ~/storage/downloads/HyperOSStrongToastAutoRestore_v06/termux/install_modes.sh
```

### Commands

```sh
# Moving rainbow / RGB animated status
strongtoastRGB -d 120 -s bold "@S9_Encryptor | RGB Test"
strongtoastRGB -s italic -d 120 "Italic rainbow"
strongtoastRGB -s normal -d 120 "Normal rainbow"

# Plain-color status with styles; original `strongtoast` command unchanged
strongtoastStyled -s normal -c '#0BFC03' -d 180 "Normal green"
strongtoastStyled -s bold -c '#0BFC03' -d 180 "Bold green"
strongtoastStyled -s italic -c '#ff05b0' -d 180 "Italic magenta"

# Countdown: 60s countdown + 30s Time over alert + vibration
# Use a short, deliberate test because the expiry alarm may vibrate for 30s.
toastTimer -s bold 1
toastTimer -s italic 1
toastTimer 30
stopToastTimer
```

### Timer color phases

- 40% → 33% remaining: smooth green → orange transition.
- 21% → 15% remaining: smooth orange → red transition.
- At zero: `Time over!`, red text, shake, 30 seconds of vibration (permission/device permitting), then native Toast clears.
- The timer uses elapsed monotonic time, not one-second request loops. During native dismissal/recovery, clock doesn't restart.

## Diagnostics

- `Strong Toast ... request sent` **only confirms the call**, not that the SystemUI hook applied. Confirm the rainbow actually changes over 6 seconds.
- LSPosed logs: search `[StrongToastEffects]`, `[StrongToastAutoRestore]`.
  - `Rainbow spans installed count=...` means the new per-segment painter was attached.
  - `Rainbow spans failed; hue-cycling fallback` indicates fallback.
  - `Timer expired. Start Time over! + 30s vibration` means the alert handler ran.
  - `Vibration unavailable` means device/permission/DND/ROM issues; do not disable DND automatically.
- For troubleshooting accidental repeated root dialogs, trigger one `strongtoastRGB` and do not run the legacy 28s `runToast` poller simultaneously.
- If SystemUI crashes or becomes unstable: disable the updated module in LSPosed and reboot/revert to V0.5.

## Test status

- Expiry effects pause while the native toast is dismissed. A timer expiring during prolonged panel use may delay vibration until restoration, or skip it if the original alert window has already ended.
- Cancelling a shake can leave the current horizontal offset if the firmware reuses that TextView without resetting it.

- The repository build uses the actual Android SDK, Gradle, Xposed API, Android lint, and APK signature verification. Termux helpers are checked separately.
- **No physical device runtime test** was performed. Rainbow spans, TextView styling, vibration behavior, and native/LSPosed compatibility depend on your ROM. Treat the behavior as experimental and keep a working previous APK for rollback.
