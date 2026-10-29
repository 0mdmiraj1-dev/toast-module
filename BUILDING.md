# Building StrongToast Extended

## Requirements

- JDK **17** (a complete JDK including `javac`).
- Android SDK platform **37.0**, Build Tools **36.0.0**, platform tools.
- Checked-in Gradle wrapper **9.6.0** (distribution SHA-256 pinned).
- Android Gradle Plugin **9.5.0**, Kotlin/Compose compiler **2.4.21**, Compose BOM **2026.09.00**.
- Internet access to Google Maven, Maven Central, Gradle and the pinned Xposed Maven mirror configured in `settings.gradle.kts`.

The project uses minSdk 26 and preserves targetSdk 28 for the legacy module's existing behavior. Compiling against the newer SDK does not change the Android 13 device requirement. AGP 9's external Kotlin compatibility switches are intentional; migration to AGP 10 is separate work.

## Android Studio

Open this directory (the one containing `settings.gradle.kts`). Select JDK 17 for Gradle, install the SDK packages above and run Gradle Sync. Choose the `release` build variant, then Build APK(s), or run `:app:assembleRelease` in the Gradle window.

`local.properties` may specify your local `sdk.dir`; never commit it. No keystore, password or signing secret is needed to build the requested unsigned release.

## Command line and CI

```sh
chmod +x gradlew
./gradlew --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
python3 scripts/verify_apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

Release output: `app/build/outputs/apk/release/app-release-unsigned.apk`.

The `native-helper/` Java sources are compiled and dexed automatically before asset merging. The resulting DEX and SHA-256 digest are embedded in the same APK. No generated bootstrap binary has to be committed; no device compiler is used.

Reports: `app/build/reports/tests/testDebugUnitTest/`, `app/build/test-results/testDebugUnitTest/`, and `app/build/reports/lint-results-release.html`.

The GitHub workflow runs on pushes to `apk-build/**` and through Run workflow. Download `StrongToast-Extended-unsigned` for the APK/checksum and `StrongToast-verification-reports` for results. Artifacts expire after 30 days; rerun the workflow to recreate them.

## Sign the update yourself

Use the same private key as your installed module. For example, with Android SDK Build Tools on your own machine:

```sh
zipalign -p -f 4 app-release-unsigned.apk StrongToast-aligned.apk
apksigner sign --ks /your/private/module.jks --ks-key-alias YOUR_ALIAS --out StrongToast-signed.apk StrongToast-aligned.apk
apksigner verify --verbose --print-certs StrongToast-signed.apk
```

Let `apksigner` prompt for passwords locally. Compare the signing certificate with your existing APK before installing. Do not add signing files or credentials to this public repository. Unsigned APKs cannot be installed directly; a differently signed APK cannot update the installed package.

`assembleDebug` is available for development but uses a development signing certificate; do not use it to update your personally signed module.

## Test limits

Robolectric API 33 tests exercise Android layout/font rendering and Compose interaction on the host. Fake controllers exist only in test sources. These tests neither run root commands nor prove compatibility with private HyperOS APIs, actual panel gestures or hardware vibration. Follow `docs/TEST_CHECKLIST.md` on the target phone after signing.

## Native UI instrumentation

`./gradlew :app:compileDebugAndroidTestKotlin` checks the separate-window preset dialog test source. Run `./gradlew :app:connectedDebugAndroidTest` on a clean Android emulator to exercise the full save/rename/activate/delete dialog interaction. This test uses a test-only transport and never launches `su` or controls SystemUI. It was not executed on a device in this build environment: Robolectric's host dialog window did not reach idle on API 33 or 35. Real DataStore CRUD and the other screen bindings have separate host coverage.

Do not install a development-signed debug APK over your personally signed module. Use a clean emulator for this UI test, or configure matching signing locally. The delivered application remains one release APK; instrumentation is a developer test only.
