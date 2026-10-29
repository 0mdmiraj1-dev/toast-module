# StrongToast Extended V0.7 — short bold/italic text truncation fix

This is a **targeted patch to V0.6**. The supplied release notes describe normal `S9_Encryptor` fitting while bold RGB renders as `S9_Encryp..` despite spare area beside the icon. That device behavior has not been independently verified here.

## Fix

- For **short** (up to 26 Unicode codepoints) bold/italic text only, measure actual text `TextPaint.measureText` against the native TextView width, minus compound padding.
- If it overflows, first try actual **sans-serif-condensed bold/italic** without losing styling. If needed, fit font size down by at most about 22%. This avoids changing the right-hand HyperCeiler icon, touch region, or XML constraints.
- Normal text and longer lines keep the original native behavior, including marquee. RGB spans and color animation are unchanged. Timer mode also uses the fit check to reduce bold/italic timer truncation.
- Existing v0.6 behavior is retained: strongtoastRGB, strongtoastStyled, toastTimer and stopToastTimer, 65-minute watchdog, shade recovery and `Time over!` vibration. The original `strongtoast` binary/DEX stays untouched.

## Build/update

The existing project retains its `applicationId`, build setup, `xposed_init`, and System UI-only LSPosed scope. The app source changes are:

- `app/src/main/java/com/s9encryptor/strongtoastextended/StrongToastEffects.java`
- `app/src/main/java/com/s9encryptor/strongtoastextended/StrongToastHook.java` (version log only)
- `app/build.gradle` (version 0.7)

An in-place update requires the same signing certificate as the installed APK. The current GitHub workflow creates a fresh debug key per runner, and the previous key was not retained, so this APK cannot directly update an earlier GitHub build. Use an original matching signing configuration when available. Reboot when testing the module. Existing V0.6 Termux helpers are retained.

## Verify

```sh
strongtoastStyled -s normal -c '#0BFC03' -d 40 'S9_Encryptor'
strongtoastStyled -s bold -c '#0BFC03' -d 40 'S9_Encryptor'
strongtoastRGB -s bold -d 120 'S9_Encryptor'
strongtoastRGB -s italic -d 120 'S9_Encryptor'
```

Look for `[StrongToastEffects] short text fit completed` in LSPosed log when bold/italic is measured too wide. The example should no longer show `..` if its length can fit within the native TextView with modest font adjustment.

**Limitations:** this intentionally does not expand the native text container because changing Xiaomi's icon layout and touch hitboxes may break SystemUI. On certain ROM builds or extra-wide fonts, very long text may still ellipsize or marquee. The repository build uses the actual Android SDK, Gradle, lint, and APK signature verification. Device runtime remains untested; keep a working APK for rollback.

The fit calculation uses the first non-zero measured width, which can still be an intermediate entrance-animation width. It does not restore the prior text size/typeface: if firmware reuses a native TextView without resetting those properties, later text may inherit the fitted font or size. Existing timer-alert limitations remain: expiry effects paused during dismissal can be delayed or skipped if the original alert window ends before restoration.
