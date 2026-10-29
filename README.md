# StrongToast Auto-Restore V0.4 (source; experimental)

This **updates** the working V0.3 LSPosed source. It preserves the package ID `com.s9encryptor.strongtoastextended` and 65-minute watchdog hook. It is not a prebuilt APK.

## V0.3 test notes supplied with this source

- Right-side icon dismiss -> restore after ~5s: **PASS**.
- Control Center -> restore: **PASS**.
- Notification Panel held <5s -> restore: **PASS**.
- Notification Panel held >=10s -> **FAIL** (Toast only returns after opening/closing Control Center).

## Why it fails

V0.3 cancels or consumes a pending retry while a shade is open. If the corresponding collapsed callback is missed, it leaves no retry scheduled. This explains the strong correlation with long shade-open periods but is not proven without your LSPosed log.

## V0.4 changes

1. Track the native right-icon dismiss as before.
2. Keep a low-cost **SystemUI Handler check every 1.5s only while a dismissed/hidden toast is awaiting restoration**; the check auto-stops after successful restore or original deadline expiry. This is *not* Termux polling and does not invoke Magisk or `su`.
3. Install a fallback hook for the native `ShadeControllerImpl.onClosingFinished()` callback.
4. Check actual Notification Panel `mExpandedFraction`/`mTracking`, Control Center `isExpand` and keyguard, falling back to previous event flags when not available. This avoids a stale expansion flag blocking recovery forever.
5. Retain the 900ms collapse restore delay; cache original content and **remaining time**, not full duration. Avoid duplicate views; handle explicit native `hide_strong_toast` command.
6. If native replay throws, retry while the original duration remains.

## Install

An in-place update requires the same signing certificate as the installed APK. The existing GitHub workflow creates a new debug key on each runner; the V0.3 GitHub signing key was not retained. This V0.4 GitHub APK therefore cannot directly update an earlier GitHub build. Use your original matching signing configuration if available. Keep System UI scope enabled when testing the module. The legacy `runToast` process is not used in V0.4.

## Test

`strongtoast -c '#0BFC03' -d 300 'S9_Encryptor'`

- Tap right icon -> notification panel open 3s -> close: should recover.
- Tap right icon -> panel open **15s** -> close: should recover usually within **1–3 seconds**.
- Tap right icon -> panel open **45s** -> close: same expected.
- Tap right icon -> Control Center open 20s -> close: should recover.
- At actual requested expiry, Toast should stop returning.

## Caveats

This source has not been exercised on the user's firmware. Native reflection fields can differ across ROM revisions. If the SystemUI process crashes repeatedly, disable the module using your Magisk/LSPosed recovery method. Android Java + LSPosed hooks require a device build and testing; local stub compilation is not runtime validation.

Logs: search LSPosed for `StrongToastAutoRestore`; look for `Restore deferred`, `Restore scheduled`, `Restore sent`, `Shade onClosingFinished hook installed`.

If both live native state fields and collapse callbacks are unavailable on a firmware, stale fallback panel flags can still defer recovery until the original expiry. Compilation and lint checks do not verify this firmware-specific behavior.
