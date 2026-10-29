# Verification checklist

Record the phone model, HyperOS build, Android version, LSPosed version and signing certificate when testing. Host tests do not count as phone tests. All target-phone boxes below are initially unchecked.

## Automated checks

`./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease` and `scripts/verify_apk.py` provide reproducible reports. Tests cover:

- HEX normalization, Unicode/trailing whitespace preservation, duration and input bounds.
- Preferences decoding, durable writes and rapid settings edits; early lifecycle events do not overwrite saved data.
- Compose navigation and Editor command binding, saved-preset activation/edit loading, timer actions and persisted settings. Real DataStore preset save/edit/rename/reorder/delete is covered independently.
- Signature permission on control reception, validated structured payloads, one-use bootstrap nonce, stale sequence rejection, private state callbacks and disconnect behavior.
- Complete command Intents parcelled before reading extras, with a class loader that rejects app classes: connection, subscription revisions/reconnection and unsubscribe must use framework ResultReceiver proxies.
- Native Android font/layout: `S9_Encryptor`, `S9_Encryptor `, `@S9_Encryptor`, Normal/Bold/Italic, emojis, rainbow spans, long marquee, countdown text, resize and listener cleanup.
- Five-second recovery, panel held over 10 seconds, preserved original deadlines, no duplicate/stale recovery, Stop cancellation, finite failure retries and timer alert cleanup while the visual toast is paused.
- Packaged launcher, legacy Xposed entrypoint, fallback PNG, bootstrap DEX digest and unsigned release contents.

## Installation and permissions — target phone

- [ ] Sign with the existing private certificate; update installs over V0.7 without losing package identity.
- [ ] LSPosed scope is only `com.android.systemui`; reboot loads the module and leaves ordinary SystemUI usable.
- [ ] App launches as a native UI without Termux installed. Root initially reports Unknown/Not granted accurately.
- [ ] First privileged Start produces the normal Magisk prompt. Denial gives a clear error; no automatic prompt loop. Explicit retry can grant access.
- [ ] Root grant and hook connection are separate indicators. Unsupported firmware reports failure without claiming visible success.
- [ ] After the 1.0.2 update and reboot, Refresh connects without a Parcel `ClassNotFoundException` for the app's callback. Start/Update/Stop work and returning to the app restores the live subscription.

## Editor and native text — target phone

- [ ] Solid status starts, updates and stops; 1-second and 60-minute boundaries work.
- [ ] Normal/Bold/Italic each show `S9_Encryptor` in full when space exists; compare with `S9_Encryptor ` and `@S9_Encryptor`.
- [ ] Repeat with emoji/Unicode, RGB and countdown text. No added whitespace, unnecessary shrinking or icon overlap.
- [ ] Genuinely long strings scroll using native marquee. Changing font/text, rotating or changing display size recalculates safely.
- [ ] HEX accepts `0bfc03`, `#0BFC03` and valid 8-digit values; invalid input cannot execute. Quotes, `$()`, backticks and newlines in text do not execute shell commands.
- [ ] HyperCeiler icon displays when available; missing package/resource falls back to StrongToast's icon without crashing.
- [ ] Rainbow moves smoothly in all font styles, survives reconstruction and stops immediately on Stop. Reduced motion gives a still spectrum.

## Countdown — target phone

- [ ] Start once; `Remaining MM:SS` updates without new Toasts each second.
- [ ] Green blends toward orange around one third, then red around 15%; no abrupt switch.
- [ ] Close app, switch apps, lock screen and return: elapsed remaining time is accurate.
- [ ] Zero shows `Time over!`, optional short shake and at most 30 seconds of vibration; cleanup occurs even when the toast was dismissed.
- [ ] Stop cancels vibration immediately. Disabled vibration, DND and device vibration policy are respected. Reduced motion suppresses shake.
- [ ] Reset stops the running timer and restores the configured setup value.

## Restoration and lifecycle — target phone

- [ ] Right icon dismissal restores after about five seconds if no panel opens.
- [ ] Dismiss, open Control Center, then close it: one toast returns with original text/style/color/icon and remaining duration.
- [ ] Repeat for Notification Panel, including keeping it open 20 seconds, 2 minutes and until expiry.
- [ ] An expired status or timer never restarts. A newly started status supersedes pending old recovery.
- [ ] Explicit Stop while dismissed/panel open prevents later restoration. Disabling auto-restore cancels recovery.
- [ ] Return to the app: state reconnects to the actual session without restarting it. Root success alone never means Visible.
- [ ] Screen off past expiry clears the status on wake. SystemUI restart disconnects the old session safely and permits a deliberate new start.

## Presets, settings and stability — target phone

- [ ] Save, edit, rename, activate and delete presets; app relaunch preserves complete text/color/font/duration/RGB/icon preferences and recent ordering.
- [ ] Dark/light/system themes, default color/font/duration/icon and alert options persist.
- [ ] Settings changes reach the active module; reduced motion/effects/restore toggles take effect safely.
- [ ] Small phone/font-scale/landscape layouts remain scrollable; keyboard does not hide inaccessible controls; TalkBack labels are usable.
- [ ] Diagnostics stay inside the expandable advanced section. Main UI shows understandable errors without raw stack traces or shell commands.
- [ ] Missing optional hook fields/methods disable affected features without repeated exceptions, boot loops or ordinary SystemUI breakage.
- [ ] No duplicate toast, permanent foreground service, recurring root process or root polling after the Activity closes.

## Recovery path

Press Stop, turn off Enhanced effects, or disable StrongToast Extended in LSPosed and reboot if the ROM behaves unexpectedly. Keep the known-working signed V0.7 APK and key. Do not mark any phone check passed until observed on that phone.

## Separate-window UI test status

`NativePresetFlowInstrumentedTest` contains the full preset dialog interaction for an Android emulator/device. Its Kotlin source is compiled in CI, but it is not counted as a passed host or device test. The Robolectric separate-window dialog failed to reach idle on API 33 and 35; the main screens and actual DataStore operations are tested separately. Run the instrumentation test and the phone checklist before marking the dialog flow verified.
