# Phase 1: V0.7 audit

Baseline: 5306e87b7f4f8badf9804381dd66a84eee219d6e. All application, legacy helper, launcher, manifest, initialization and build files were inspected.

Package com.s9encryptor.strongtoastextended and legacy assets/xposed_init -> StrongToastHook remain unchanged. Scope: com.android.systemui. Original build: AGP8.7.3, Gradle8.9, Java8, min26/compile35/target28, Xposed82 compileOnly from rovo89 official Maven mirror. There were no activities, app runtime dependencies or communication state.

Hooks to retain: MIUIStrongToast.onAttachedToWindow watchdog65min; MIUIStrongToastControl.showCustomStrongToast(Bundle) capture; setStatus explicit hide; MIUIStrongToast.hideStrongToast/onComplete; shade updateStateInternal, CentralSurfaces full expansion, ShadeControllerImpl.onClosingFinished and ControlPanelWindowManager.onExpandChange. Recovery generation/deadlines,5s dismissal,900ms collapse,1500ms deferred panel checks remain inside SystemUI.

Existing helper uses root app_process plus StatusBarManager.setStatus(1,strong_toast_action,Bundle). Native contract is text_bitmap, package_name drawable package, param JSON with left text/color and right icon, duration milliseconds, s9_mode/style/countdown metadata. Timer includes30s alert tail. Termux previously compiled and installed a separate DEX. It is archived source only after migration; no runtime Termux dependency is permitted. Icons use HyperCeiler com.sevtinge.hyperceiler/ic_hyperceiler or packaged PNG fallback, validated through Android PackageManager.

Integration: signature-protected package-scoped app-to-SystemUI commands with private ResultReceiver callbacks, structured validation and monotonic request sequencing. A packaged Java DEX, invoked only through authorized su, bootstraps the existing private StatusBar channel and captures the native controller. No exported root service, unprotected control file or recurring root polling. SystemUI owns current session state; app queries/ subscribes while visible. Root transport must distinguish delivery from native visibility.

Fixes required: replace font shrinking with bounded native TextView/container layout fitting and glyph overhang allowance; restore baseline properties/listeners. Separate timer expiry/cleanup from visual frame pause; reset shake offsets and handle vibration failure. Firmware fields are guarded capabilities, not guarantees.

Delivery signing: user explicitly requested unsigned APK and will sign with their own matching certificate. No signing secret is required or published.
