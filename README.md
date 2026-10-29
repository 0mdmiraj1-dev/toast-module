# StrongToast Extended

One native Android APK containing a Kotlin/Jetpack Compose control app and the existing legacy LSPosed module for Xiaomi HyperOS SystemUI. Application ID: `com.s9encryptor.strongtoastextended`; version **1.0.2 (102)**. This continues the V0.7 project in the same package.

## Install and update

1. Download `StrongToast-Extended-unsigned` from the latest successful [native app build](https://github.com/0mdmiraj1-dev/toast-module/actions?query=branch%3Aapk-build%2Fnative-app). Extract the artifact ZIP.
2. **Sign `app-release-unsigned.apk` with your existing module's private signing key.** The delivered release is deliberately unsigned. Updating an installed package requires the same signing certificate; the package ID alone does not preserve update compatibility.
3. Install the signed APK as an update. In LSPosed, enable **StrongToast Extended** and select **System UI (`com.android.systemui`) only**. Reboot after installing or updating hook code.
4. Open the app. Refresh to check the System UI connection. On the first Start or root-access action, approve the normal Magisk superuser prompt. Denial is handled without repeated automatic requests.
5. Use the Editor or Timer to start a status. The app can then be closed; SystemUI manages effects, countdown and recovery.

The target is a rooted Xiaomi/Poco running Android 13 with compatible HyperOS Strong Toast classes and legacy LSPosed API 82. Private ROM APIs differ across firmware: an unsupported feature is reported through diagnostics. No SystemUI APK patching, Termux installation, permanent root daemon, companion app or foreground service is required.

## Native controls

- **Dashboard:** live preview, acknowledged active state, remaining time, color mode, Start/Update/Stop, connection status and recent presets.
- **Status editor:** Unicode/emojis, custom HEX color (with or without `#`), Normal/Bold/Italic, solid or animated rainbow, minute/second duration up to 60 minutes, icon selection and preset saving.
- **Countdown:** a single start request; SystemUI updates `Remaining MM:SS` once per second using elapsed time. Colors blend from green through orange to red. Completion shows `Time over!` for up to 30 seconds, with optional short shake and vibration. Stop cancels the alert.
- **Presets:** save, edit, rename, activate and delete complete styles, stored locally with DataStore.
- **Settings:** dark/light/system theme, defaults, automatic restoration, reduced motion, effects safety toggle, timer alerts, connection status and expandable diagnostics.

HyperCeiler's `com.sevtinge.hyperceiler:drawable/ic_hyperceiler` is supported. A packaged PNG is used if its package or drawable is missing. Package detection uses Android APIs rather than shell package-manager commands.

## Preserved behavior and fixes

The V0.7 native show/hide/watchdog and panel hooks remain in place. Clicking the right icon dismisses the status; recovery waits approximately five seconds if no panel is open, or until Notification Panel/Control Center closes. Long panel sessions retain the original deadline and settings. Explicit Stop, expiry and stale request generations cancel recovery. Retry failures have a finite budget.

The text fix measures the styled native TextView, glyph overhang and actual space before the right icon. It adjusts bounded internal layout width/padding without adding trailing whitespace or shrinking the font. Fitting text loses unnecessary ellipsis; long text retains native marquee. Layout listeners coalesce changes and are cleaned up with the session.

Commands use a signature-protected SystemUI receiver with private Binder-backed replies, validation, request IDs and sequence fencing. A build-generated Java/DEX bootstrap uses an authorized, bounded root session only when the native controller needs preparation. User text never becomes shell source. Successful root execution is not presented as proof of visible rendering.

Screen-off time counts toward original elapsed deadlines. SystemUI process death ends the in-memory session; the app reconnects and reports state, without silently restarting an expired timer. Vibration remains subject to device policy and DND. Reduced motion keeps rainbow static and suppresses shake.

## Build and verification

Open the root directory in Android Studio, sync Gradle and select the release variant. See [BUILDING.md](BUILDING.md) for exact SDK/JDK versions, Gradle commands and signing instructions. The GitHub workflow runs unit/native Android tests, release lint, release assembly and APK contents/integrity checks, then uploads the unsigned APK and reports.

Host tests cover validation, real DataStore preset CRUD, MVVM races, command permissions/order, native font/layout regressions, app control bindings, timer cleanup and restoration. The complete preset dialog interaction is retained as an Android instrumentation test; the host runner did not become idle for that separate dialog window, so a dialog pass is not claimed. They use Robolectric and test-only transports; they do not execute Magisk or emulate Xiaomi firmware. **Real target-phone validation remains required:** use [the test checklist](docs/TEST_CHECKLIST.md). No real-device pass is claimed.

[Migration notes](docs/MIGRATION.md) explain the replaced Termux components. [Dependency audit](docs/DEPENDENCY_AUDIT.md) records the V0.7 baseline. The retained `termux/` directory is historical source, not an application dependency.

If needed, turn off Enhanced effects in Settings, press Stop, or disable the module in LSPosed and reboot. Keep your known-working signed APK for rollback; Android may require uninstalling before installing a lower version code.

## 1.0.2 cross-process callback fix

The supplied Android 13 device logs identify the failed connection: SystemUI received the command, but Android could not unmarshal the app's anonymous `ModuleClient` ResultReceiver subclass. The callback failed before a reply could be sent. An enabled LSPosed scope and granted root were not the cause of this error.

All command and unsubscribe callbacks now pass through `ResultReceiver.CREATOR` before entering an Intent. Only the framework class name and its private Binder endpoint cross the process boundary; the local callback implementation stays in the app. Signature permission, private callbacks, request IDs and sequence checks remain enforced. Regression transports parcel the complete Intent with a loader that rejects app classes, including subscription updates and unsubscribe. The previous host test decoded extras with the app loader too early and did not model this failure.

Sign the unsigned APK with the existing certificate, install the update, reboot, and Refresh. Check that System UI connects, then test Start/Update/Stop and reopening the app. This release addresses the exception observed in the logs; successful operation on the target phone still needs verification.

## 1.0.1 connection diagnostics

The headless root helper no longer invokes `ActivityThread.systemMain()` (which initializes Xiaomi graphics/performance services); root authorization does not create a framework Context. SystemUI receiver registration starts before Application onCreate and can recover from a captured native controller Context. Commands request foreground broadcast scheduling and diagnostics record whether the app actually holds its control permission. Signature permission enforcement remains unchanged.

After updating the signed APK, reboot before checking connection. Root UID 0 and an enabled LSPosed scope do not establish that the new hook code is loaded. If disconnected, provide the LSPosed module log lines containing StrongToast, including receiver registration or hook-load errors. The reported ENOENT framework traces followed by UID 0 were not proof of root denial. Target-phone connection remains to be rechecked.
