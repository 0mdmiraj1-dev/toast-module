# Migration from the V0.7 module

The application ID remains `com.s9encryptor.strongtoastextended`; the same APK contains the launcher application and the legacy LSPosed entrypoint `StrongToastHook`. The module scope remains **System UI** (`com.android.systemui`). The original 65-minute watchdog and native restoration hooks are retained.

The native app replaces `strongtoastRGB`, `strongtoastStyled`, `toastTimer` and `stopToastTimer` with the Editor, Timer and Dashboard controls. A build-generated Java/Dex bootstrap is packaged inside the APK. There is no dependency on Termux, on-device `javac`/`d8`, a shell installer, or a separate companion package. The `termux/` source directory is retained as the V0.7 compatibility reference, not as an app runtime component.

Native requests preserve the existing `text_bitmap` Bundle and drawable icon contract. Icon discovery uses Android PackageManager/resource APIs, with HyperCeiler (`com.sevtinge.hyperceiler`, `ic_hyperceiler`) and the packaged PNG fallback. No non-interactive package-manager shell lookup is used.

Ongoing effects, countdown, expiration and panel recovery live inside SystemUI. Closing the Activity unsubscribes the UI; it does not stop a status or create a permanent foreground service. On return the app queries actual module state, including original elapsed-time deadlines. SystemUI process death clears that native session; the app must not silently restart it.

Root authorization is requested through Magisk's supported `su` interface on a privileged operation. Denial is an explicit state and does not trigger repeated prompts. Normal controls and state callbacks use the signature-protected command channel. No arbitrary text is interpolated into shell code, and no unrestricted root service or world-writable control file is exposed.

## Signing and installation

The user requested an **unsigned release APK** and will sign it using the same private certificate as the installed module. The repository does not contain a private signing key. An unsigned APK must be signed before installation; a certificate mismatch cannot update the existing package. Preserve a known-working signed APK for rollback.

After signing/installing, enable StrongToast Extended in LSPosed for System UI only and reboot. Use the app's Root access action or first Start action to approve Magisk. The app distinguishes root authorization, module connection and actual native toast visibility.

## Device verification

Gradle compilation, unit tests and Android resource checks do not establish HyperOS device compatibility. Follow `TEST_CHECKLIST.md` on the target phone, especially private ROM field support, font measurements, panel gestures, native icon loading and vibration/DND behavior. Disable experimental effects in Settings if needed; disable the module in LSPosed and reboot if SystemUI becomes unstable.
