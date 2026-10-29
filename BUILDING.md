# Build this source project

Requires JDK 17 or newer, Android SDK Platform 35 and Build Tools 34.0.0.
The included wrapper downloads Gradle 8.9 and verifies its SHA-256 checksum.
Android Gradle Plugin remains 8.7.3. The Xposed API dependency uses the original
rovo89/XposedBridge repository's gh-pages Maven mirror.

## Android Studio

Open the directory containing settings.gradle, complete Gradle Sync, then use
Build APK(s). The debug APK is app/build/outputs/apk/debug/app-debug.apk.

## Command line

Set ANDROID_HOME to your Android SDK directory, or configure sdk.dir in your
local, untracked local.properties file. Then run:

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

On Windows use gradlew.bat. The debug build is signed automatically with a
local development key. It is not a production release build; builds from
separate machines may use different debug signing keys.

## GitHub Actions

The Build StrongToast APK workflow runs when an apk-build/** branch is pushed.
It also declares workflow_dispatch for manual use once the workflow is on the
default branch. After a successful run, download StrongToast-Extended-debug
from the run's Artifacts section and extract the APK. Artifacts expire after
14 days. The workflow also uploads its Android lint report.

## What the APK is

This is a legacy LSPosed module with no launcher Activity. It must be enabled
in LSPosed for com.android.systemui. Successful compilation does not confirm
compatibility with a particular HyperOS firmware. The separate Termux helper
script mentioned in the original README was not included in the uploaded ZIP.

The original targetSdk 28 is retained. Its Google Play target-policy lint check
is reported as a warning because this legacy module is installed directly.
Other lint errors still fail the build.
