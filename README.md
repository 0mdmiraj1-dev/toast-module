# StrongToast Extended Auto-Restore V0.3 (experimental)

This updates the `StrongToast Extended` LSPosed module to V0.3. It keeps the package name (`com.s9encryptor.strongtoastextended`), hook entrypoint, and duration CLI. Automated Gradle builds check compilation and Android lint; **on-device behavior has not been tested on your exact SystemUI**.

Signing depends on the build environment. The existing GitHub workflow creates a debug APK with a fresh runner-generated key. The earlier GitHub APK signing key was not retained, so this build cannot be assumed to install over it. A matching original signing key is required for an in-place update.

## Why V0.2 was insufficient

On the user's device the native toast itself blocks pulling the Notification Shade. The user taps the right-side icon first; the toast disappears, and *then* they open the shade or Control Center. V0.2 primarily watched shade expand/collapse events, and did not explicitly register a native hide. It could work if the shade expanded and collapsed, but not reliably for right-icon dismissal alone.

## New V0.3 logic

- Keeps the existing `MIUIStrongToast.onAttachedToWindow` **65-minute watchdog** extension.
- Captures 61–3600-second `text_bitmap` requests; stores the original Bundle (color, icon, text) and monotonic expiry time.
- Hooks native `MIUIStrongToast.hideStrongToast()` and `MIUIStrongToast.onComplete()` to record dismissal (not the click directly). This is more robust across right-icon click and animation paths.
- If the toast disappears, starts a **5-second fallback** before restoring. This gives time to pull down Notification Shade.
- When Shade / Control Center opens, cancels any pending restore; after it closes, tries to restore after **900ms**, *only if* the toast is genuinely hidden and has remaining duration. It does not restore while a panel or keyguard is open.
- If nothing is opened after tapping the icon, fallback tries to show the toast again 5 seconds later.
- No repetitive `su`, no periodic Termux polling, no new 30-minute timer on replay.
- Short/replacement toast requests clear the cache. An explicit native `hide_strong_toast` command cancels replay.

## Build/install

1. Update the **existing Codex project** with this ZIP's `StrongToastHook.java`, `app/build.gradle`, and `AndroidManifest.xml`; **keep your existing Gradle wrapper, GitHub Actions and signing setup**.
2. Build the APK. An in-place install over v0.1/v0.2 requires the same signing certificate. GitHub debug builds from different runs use different keys; use your original matching signing configuration when available. Do not assume this APK can update an already installed GitHub build.
3. LSPosed → StrongToast Extended → enable only **System UI** scope. Reboot.
4. Stop legacy polling loop (`stopToast`), then run `strongtoast -c '#0BFC03' -d 300 'S9_Encryptor'`.
5. Test A: tap right-side icon, do **not** open panel; Toast should be attempted again after about 5 seconds.
6. Test B: tap icon, immediately open Notification Shade, hold 10s, close; toast should be attempted about 900ms after closing.
7. Test C: same with Control Center; then wait for actual 5-minute expiry to verify it does not restart beyond expiry.

## Caveats

- Based on published decompilation of a comparable Xiaomi SystemUI, not a dump from this exact handset. Some callback names may differ and runtime checks still matter.
- Due to timing/layout differences, **not guaranteed** to restore on every gesture; log lines help identify missing callbacks.
- The `hideStrongToast` callback might fire for a native timeout or intentionally dismissing an unrelated toast. Time check and active-instance check reduce false restorations. Manual stop should use a short replacement toast or native hide action (v0.3 handles both).
- The safety timer extends only the hard-coded native 30s watchdog; the normal duration timer still controls expiry.
- Restarting SystemUI clears cached requests.
- LSPosed SystemUI hooks may cause instability; have a way to disable the module if necessary.

## Logs

In LSPosed logs search for `StrongToastAutoRestore`. Look for:
- `Captured long toast`
- `Native toast dismissed`
- `Restore scheduled`
- `Restore sent`
- `Restore deferred`

Source evidence used: native `MIUIStrongToast.hideStrongToast`, `onComplete`, `MIUIStrongToastControl.showCustomStrongToast(Bundle)`, `ShadeExpansionStateManager.updateStateInternal(int)` and `ControlPanelWindowManager.onExpandChange(boolean)` in the Xiaomi decompilation at https://github.com/ymdzq/k60_statusbar_lyric.

## Additional behavior limits

A restore deferred while keyguard is showing has no dedicated unlock retry in this source; another panel-close event may be needed. Partial shade-collapse behavior also depends on the firmware exposing the native panel state fields checked by the hook.
