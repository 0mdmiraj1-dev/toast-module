# StrongToast Auto-Restore V0.5 — RGB + Timer

Experimental V0.5 update for the HyperOS 1 / Android 13 LSPosed module. Gradle build and Android lint are checked for the repository build; device behavior remains unverified.

**V0.5 adds only feature 2 and feature 3.** Long status text continues to start marquee from the beginning after native restore (unchanged). Preserve existing 65-minute watchdog, notification/control panel restoration, cache, and expiry logic.

## Included files

- `app/src/main/java/com/s9encryptor/strongtoastextended/StrongToastHook.java` — V0.4 with small integration points for effect sessions
- `app/src/main/java/com/s9encryptor/strongtoastextended/StrongToastEffects.java` — new SystemUI-side shader animation and timer updating
- `termux/S9ToastModes.java` — **separate** standalone app_process helper using the same private StatusBar API, **does not modify** existing StrongToast.java or strongtoast DEX
- `termux/install_modes.sh` and command launchers — separate Termux commands

## Module install

The applicationId remains `com.s9encryptor.strongtoastextended`, with the existing Xposed entrypoint and System UI scope. The APK includes `StrongToastEffects.java`. Enable the module for System UI and reboot when testing.

An in-place update requires the same signing certificate as the installed APK. The current GitHub workflow creates a new debug signing key for each run, and the previous GitHub key was not retained. This APK cannot directly update an earlier GitHub build. Use your original matching signing configuration when available. Keep a recovery path to disable the module if SystemUI becomes unstable.

## Install Termux clients

Extract ZIP into Termux-accessible Downloads (Termux storage permission should be granted):

```bash
pkg install unzip python openjdk-17 d8
unzip -o ~/storage/downloads/HyperOSStrongToastAutoRestore_v05_Source.zip -d ~/storage/downloads/
bash ~/storage/downloads/HyperOSStrongToastAutoRestore_v05/termux/install_modes.sh
```

This installer builds a new `/data/local/tmp/s9-toast-modes/classes.dex`, creates `strongtoastRGB`, `toastTimer`, `stopToastTimer` and `s9-toast-client`, and copies drawable info from `~/.strongtoast-build/StrongToast.java` (including HyperCeiler custom icon). It **does not modify** your existing `strongtoast`/`runToast` commands or their DEX.

## Commands

```bash
strongtoastRGB -d 300 'S9_Encryptor Rainbow'
strongtoastRGB -d 1800 'Long scrolling developer quote...'
toastTimer 30
stopToastTimer
```

- `strongtoastRGB` default duration is 1800s, can use `-d 1..3600`.
- `toastTimer N` takes `N` minutes 1..60. Initial text displays `Remaining MM:SS`, and updates once per second without creating a new Toast.
- Timer uses `SystemClock.elapsedRealtime()` and the **original** deadline captured when first displayed. Restoring a dismissed Toast doesn't restart the countdown.
- Hue transition: green until 35% left, smooth green→orange during 35%→33%; orange until 17%, smooth orange→red during 17%→15%; red until end.
- `stopToastTimer` sends native `hide_strong_toast` **for the current Strong Toast**, so it can also hide an RGB status if one is currently displayed.
- RGB is a moving repeating multi-color shader on the native `mLeftTextView`, ~15 frames/sec. The existing native scrolling/marquee behavior continues; after a new View is created, marquee restarts at the beginning.
- When panels are open, the V0.4 native recovery logic controls visibility. No recurring Termux root calls are needed. The modes client needs `su` once at startup/stop only.

## Tests after installation

1. `strongtoastRGB -d 120 'S9 RGB TEST'` → verify gradient moves visibly and duration stays >30s.
2. Right icon tap → wait 5–7s → verify RGB returns.
3. RGB → icon tap → notification shade open 15–45s → close → verify restore; note marquee restarts.
4. `toastTimer 2` → verify text shows `Remaining 02:00` then `01:59` and decreases while switching apps.
5. Timer → icon tap → shade open 15s → close → verify it returns at **remaining** time, not reset.
6. Short development test for color transition: `toastTimer 1`; green→orange near 20s left, orange→red near 9s left.
7. `stopToastTimer` → verify current toast hides without coming back.

## Debugging

In LSPosed logs search `StrongToastAutoRestore` or `StrongToastEffects`.

Termux helper stderr: `cat ~/.strongtoast-modes/last-error.log`.

## Safety/limitations

Not tested on the user's device yet. The repository builds a signed debug APK with the Android SDK and Xposed API; compilation and lint checks do not validate firmware behavior. Xiaomi ROM field names can differ. Shader rendering and timing are **experimental** until device testing. This module does not survive SystemUI process death with its mode/deadline state; re-run the client after such a restart. The native source is not a public Android API. Use LSPosed recovery to disable the module if SystemUI fails repeatedly.
