# HyperOS StrongToast Extended (experimental LSPosed source project)

## What it does

On some HyperOS 1 (Android 13) SystemUI builds, `com.android.systemui.toast.MIUIStrongToast.onAttachedToWindow()` schedules a hardcoded 30,000 ms hide callback via `mLastHandler` and `mHideStrongToastRunnable`. This LSPosed legacy module reschedules **only that safety callback** to 65 minutes.

The actual toast request also schedules a second callback using its `duration` from the Bundle; the request must still use the intended duration, e.g. `900000` ms for 15 minutes. For that, the accompanying `strongtoast_extend_duration.sh` patches the preexisting Termux helper to permit up to 3600 seconds. It preserves the current icon and text-color setup by editing only the duration guards.

## Requirements

- Rooted HyperOS system with LSPosed (legacy Xposed modules supported)
- Android Studio / JDK 17, Gradle Sync, Android SDK 35
- Existing working Termux `strongtoast` helper from this conversation

## Build

1. In Android Studio: **Open** this project folder.
2. Sync Gradle, then **Build > Build APK(s)**.
3. Install the APK; enable it in LSPosed Manager.
4. Select **only** scope `com.android.systemui` (NOT Android/System Framework).
5. Restart SystemUI / reboot the phone (reboot recommended).
6. In Termux run `bash ~/storage/downloads/strongtoast_extend_duration.sh`.
7. Test: `strongtoast -c '#0BFC03' -d 90 '90-second visibility test'`.
8. Then: `strongtoast -c '#0BFC03' -d 900 '15-minute test'` or `runToastLong`.

LSPosed logs should show `[StrongToastExtended] Hook registered...`.

If `compileOnly 'de.robv.android.xposed:api:82'` cannot be fetched, obtain a trusted Xposed API 82 JAR locally and change dependency to `compileOnly files('libs/api-82.jar')`.

## Important limitations

- **Not tested on your exact POCO X4 Pro firmware**. The referenced disassembly is from another HyperOS build.
- If your ROM renamed, moved, or changed `MIUIStrongToast`, this hook won't work. It logs the issue; no SystemUI APK is modified.
- Changing the timeout does NOT guarantee the toast stays up if the notification shade/control center or other SystemUI events dismiss it.
- The existing `runToast` script still refreshes every 28 seconds. Use **`runToastLong`** after enabling the module to send once; it does not auto-restore after a panel dismissal.
- `stopToast` from the old loop doesn't guarantee dismissal of a manually submitted 15+ minute toast; try requesting a 1-second replacement: `strongtoast -d 1 ' '`. On some ROM builds replacement may be ignored.
- Any SystemUI hook could cause crashes. Keep recovery access to Magisk/LSPosed. Disable the module if SystemUI becomes unstable.

## Source evidence

Decompiled source reference: https://github.com/ymdzq/k60_statusbar_lyric/blob/535d7a3b2c61ced49f76b6da555676570fd46443/MiuiSystemUI_decompile_xml/smali/classes/com/android/systemui/toast/MIUIStrongToast.smali

`onAttachedToWindow` schedules 0x7530 = 30000 ms; `showCustomStrongToast` uses `StrongToastModel.getDuration()` in `mShowStrongToastHandler.postDelayed`.
