package com.s9encryptor.strongtoastextended;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import org.json.JSONObject;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperOS 1 / Android 13 LSPosed SystemUI-only module (V0.7).
 *
 * 1) Keep the proven 65-minute independent onAttachedToWindow() watchdog hook.
 * 2) Capture long text_bitmap Strong Toasts (61-3600 seconds).
 * 3) Detect disappearance through native hideStrongToast()/onComplete(), even
 *    BEFORE the user is able to open the notification / control panel.
 * 4) When a panel opens, keep a low-overhead deferred native state check until it closes.
 * 5) If the toast was manually dismissed but no panel is opened, retry after 5s.
 * 6) Restore only the REMAINING duration (clock: elapsedRealtime), without su.
 * 7) Native rainbow spans, text styles, countdown and 30-second timer expiry alarm.
 *
 * All mutable state is confined to SystemUI's main looper. Does not
 * intercept external arbitrary notifications or make the toast non-dismissible.
 */
public final class StrongToastHook implements IXposedHookLoadPackage {
    private static final String TAG = "[StrongToastAutoRestore] ";
    private static final String SYSUI = "com.android.systemui";
    private static final long WATCHDOG_MS = 65L * 60_000L;
    private static final long MAX_STICKY_MS = 60L * 60_000L + 30_000L;
    private static final long MIN_STICKY_MS = 60_000L;
    private static final long COLLAPSE_RESTORE_DELAY_MS = 900L;
    private static final long DISMISS_RESTORE_DELAY_MS = 5_000L;
    private static final long MIN_REMAINING_MS = 1_000L;
    private static final long PANEL_RECHECK_MS = 1_500L;

    private static boolean hooksInstalled;
    private static Handler main;
    private static Object toastControl;
    private static Bundle saved;
    private static long expiresAt;
    private static boolean replaying;
    private static boolean nativeDismissed;
    private static boolean shadeExpanded;
    private static boolean ccExpanded;
    private static int generation;
    private static Runnable pendingRestore;
    private static Runnable finalExpiry;
    private static long countdownEnd;
    private static long countdownDuration;
    private static int replayFailures;
    private static final int MAX_REPLAY_FAILURES = 5;

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam param) {
        if (!SYSUI.equals(param.packageName) || (param.processName != null && !SYSUI.equals(param.processName)) || hooksInstalled) return;
        hooksInstalled = true;
        main = new Handler(Looper.getMainLooper());
        StrongToastBridge.install(param.classLoader, main);
        installNativeWatchdog(param.classLoader);
        installToastCapture(param.classLoader);
        installNativeHideEvents(param.classLoader);
        installShadeEvents(param.classLoader);
        installControlCenterEvents(param.classLoader);
        XposedBridge.log(TAG + "Native companion hooks installed; see bridge capabilities for ROM support");
    }

    private static void onMain(Runnable action) {
        if (main == null) return;
        Runnable guarded = new Runnable() { @Override public void run() {
            try { action.run(); } catch (Throwable error) { log("main callback failed", error); }
        }};
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run();
        else main.post(guarded);
    }

    private static void installNativeWatchdog(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.toast.MIUIStrongToast", cl,
                "onAttachedToWindow", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            StrongToastEffects.prepareNativeLayout(p.thisObject);
                            Handler handler = (Handler) XposedHelpers.getObjectField(p.thisObject, "mLastHandler");
                            Runnable hide = (Runnable) XposedHelpers.getObjectField(p.thisObject, "mHideStrongToastRunnable");
                            if (handler != null && hide != null) {
                                handler.removeCallbacks(hide);
                                handler.postDelayed(hide, WATCHDOG_MS);
                            }
                        } catch (Throwable e) { log("watchdog error", e); }
                    }
                });
            StrongToastBridge.capability("watchdog", true);
            XposedBridge.log(TAG + "65-minute watchdog hook installed");
        } catch (Throwable e) { StrongToastBridge.capability("watchdog", false); log("watchdog hook missing", e); }
    }

    private static void installToastCapture(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.toast.MIUIStrongToastControl", cl,
                "showCustomStrongToast", Bundle.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(final MethodHookParam p) {
                        if (p.hasThrowable() || replaying || !(p.args[0] instanceof Bundle)) return;
                        final Bundle request = new Bundle((Bundle) p.args[0]);
                        final Object control = p.thisObject;
                        onMain(new Runnable() {
                            @Override public void run() {
                                String category = request.getString("strong_toast_category", "");
                                String pkg = request.getString("package_name", "");
                                long duration = request.getLong("duration", 0L);
                                StrongToastBridge.captureController(control);
                                // Do not replay a different app's old toast over a new request.
                                if (saved != null) clearCache("replaced by another request");
                                String effect = request.getString("s9_mode", "");
                                boolean effectMode = "rgb".equals(effect) || "timer".equals(effect) || "styled".equals(effect);
                                if (!"text_bitmap".equals(category) || pkg.isEmpty() ||
                                    duration < (effectMode ? 1_000L : MIN_STICKY_MS + 1L) ||
                                    duration > MAX_STICKY_MS) return;
                                saved = new Bundle(request);
                                toastControl = control;
                                long capturedAt = SystemClock.elapsedRealtime();
                                expiresAt = capturedAt + duration;
                                countdownDuration = Math.max(1_000L, Math.min(duration,
                                    request.getLong("s9_countdown_ms", duration)));
                                countdownEnd = "timer".equals(effect) ? capturedAt + countdownDuration : expiresAt;
                                replayFailures = 0;
                                nativeDismissed = false;
                                generation++;
                                scheduleFinalExpiry();
                                StrongToastBridge.onRequestCaptured(request);
                                XposedBridge.log(TAG + "Captured toast durationMs=" + duration + " effect=" + effect);
                                StrongToastEffects.start(effect, request.getString("s9_style", "normal"),
                                    request.getLong("s9_countdown_ms", duration), control, main, expiresAt);
                            }
                        });
                    }
                });
            StrongToastBridge.capability("capture", true);
            XposedBridge.log(TAG + "Long-toast capture hook installed");
        } catch (Throwable e) { StrongToastBridge.capability("capture", false); log("capture hook missing", e); }

        try {
            // An explicit status-bar 'hide' command must cancel automatic replay.
            XposedHelpers.findAndHookMethod("com.android.systemui.toast.MIUIStrongToastControl", cl,
                "setStatus", int.class, String.class, Bundle.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!(p.args[2] instanceof Bundle)) return;
                        Bundle nativeRequest = (Bundle) p.args[2];
                        if (nativeRequest.containsKey("s9_bootstrap_nonce")) {
                            // Bootstrap requests never reach normal native toast handling,
                            // including invalid/expired nonce attempts.
                            p.setResult(null);
                            try { StrongToastBridge.handleBootstrap(p.thisObject, nativeRequest); }
                            catch (Throwable error) { log("bootstrap rejected", error); }
                            return;
                        }
                        if (!"strong_toast_action".equals(p.args[1])) return;
                        String action = nativeRequest.getString("status_bar_strong_toast", "");
                        if ("hide_strong_toast".equals(action)) {
                            onMain(new Runnable() { @Override public void run() {
                                clearCache("explicit native hide command");
                            }});
                        }
                    }
                });
            StrongToastBridge.capability("explicitHide", true);
        } catch (Throwable e) { StrongToastBridge.capability("explicitHide", false); log("native hide action hook missing", e); }
    }

    private static void installNativeHideEvents(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.toast.MIUIStrongToast", cl,
                "hideStrongToast", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(final MethodHookParam p) {
                        markHiddenIfCurrent(p.thisObject, "hideStrongToast");
                    }
                });
            StrongToastBridge.capability("nativeHide", true);
        } catch (Throwable e) { StrongToastBridge.capability("nativeHide", false); log("native hide hook missing", e); }
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.toast.MIUIStrongToast", cl,
                "onComplete", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(final MethodHookParam p) {
                        markHiddenIfCurrent(p.thisObject, "onComplete");
                    }
                });
            StrongToastBridge.capability("nativeComplete", true);
            XposedBridge.log(TAG + "Native completion hook installed");
        } catch (Throwable e) { StrongToastBridge.capability("nativeComplete", false); log("native complete hook missing", e); }
    }

    private static void markHiddenIfCurrent(final Object toast, final String reason) {
        onMain(new Runnable() { @Override public void run() {
            if (saved == null || toastControl == null || replaying) return;
            if (!currentNativeToastIs(toast)) return;
            if (expired()) { clearCache("duration complete"); return; }
            nativeDismissed = true;
            StrongToastEffects.pause();
            StrongToastBridge.onVisibilityChanged();
            XposedBridge.log(TAG + "Native toast dismissed: " + reason);
            // The user must tap the icon first, then pull down the panel.
            // Allow 5 seconds for the gesture instead of instantly blocking it.
            scheduleRestore(DISMISS_RESTORE_DELAY_MS, "native dismissal fallback");
        }});
    }

    private static boolean currentNativeToastIs(Object toast) {
        try { return XposedHelpers.getObjectField(toastControl, "mMiuiStrongToastCallBack") == toast; }
        catch (Throwable e) { return false; }
    }

    private static void installShadeEvents(ClassLoader cl) {
        try {
            // 0 = closed, 1 = opening, 2 = open in referenced MIUI/HyperOS SystemUI.
            XposedHelpers.findAndHookMethod("com.android.systemui.shade.ShadeExpansionStateManager", cl,
                "updateStateInternal", int.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(final MethodHookParam p) {
                        final int state = (Integer)p.args[0];
                        onMain(new Runnable() { @Override public void run() {
                            shadeExpanded = state != 0;
                            StrongToastBridge.onVisibilityChanged();
                            if (saved != null && (nativeDismissed || !nativeToastVisible())) {
                                scheduleRestore(shadeExpanded ? PANEL_RECHECK_MS : COLLAPSE_RESTORE_DELAY_MS,
                                    shadeExpanded ? "shade expanded (keep recovery armed)" : "shade collapsed");
                            }
                        }});
                    }
                });
            StrongToastBridge.capability("shadeState", true);
            XposedBridge.log(TAG + "Shade state hook installed");
        } catch (Throwable e) { StrongToastBridge.capability("shadeState", false); log("shade state hook missing", e); }

        try {
            // Additional ROM callback for full shade expanded/collapsed state.
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.phone.CentralSurfacesImpl", cl,
                "onShadeExpansionFullyChanged", Boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(final MethodHookParam p) {
                        final boolean expanded = Boolean.TRUE.equals(p.args[0]);
                        onMain(new Runnable() { @Override public void run() {
                            shadeExpanded = expanded;
                            StrongToastBridge.onVisibilityChanged();
                            if (saved != null && (nativeDismissed || !nativeToastVisible())) {
                                scheduleRestore(expanded ? PANEL_RECHECK_MS : COLLAPSE_RESTORE_DELAY_MS,
                                    expanded ? "shade fully expanded (keep recovery armed)" : "shade fully closed");
                            }
                        }});
                    }
                });
            StrongToastBridge.capability("shadeFull", true);
            XposedBridge.log(TAG + "CentralSurfaces shade hook installed");
        } catch (Throwable e) { StrongToastBridge.capability("shadeFull", false); log("CentralSurfaces hook missing", e); }

        // Xiaomi's NotificationPanelViewController.endClosing() invokes this
        // callback in several ROM builds even if expansion-state callbacks are late.
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.shade.ShadeControllerImpl", cl,
                "onClosingFinished", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        onMain(new Runnable() { @Override public void run() {
                            if (saved != null && (nativeDismissed || !nativeToastVisible())) {
                                scheduleRestore(COLLAPSE_RESTORE_DELAY_MS,
                                    "shade onClosingFinished callback");
                            }
                        }});
                    }
                });
            StrongToastBridge.capability("shadeClosed", true);
            XposedBridge.log(TAG + "Shade onClosingFinished hook installed");
        } catch (Throwable e) { StrongToastBridge.capability("shadeClosed", false); log("Shade onClosingFinished hook missing", e); }
    }

    private static void installControlCenterEvents(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.controlcenter.phone.ControlPanelWindowManager", cl,
                "onExpandChange", boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(final MethodHookParam p) {
                        boolean value;
                        try { value = XposedHelpers.getBooleanField(p.thisObject, "isExpand"); }
                        catch (Throwable ignored) { value = (Boolean)p.args[0]; }
                        final boolean expanded = value;
                        onMain(new Runnable() { @Override public void run() {
                            ccExpanded = expanded;
                            StrongToastBridge.onVisibilityChanged();
                            if (saved != null && (nativeDismissed || !nativeToastVisible())) {
                                scheduleRestore(expanded ? PANEL_RECHECK_MS : COLLAPSE_RESTORE_DELAY_MS,
                                    expanded ? "control center expanded (keep recovery armed)" : "control center closed");
                            }
                        }});
                    }
                });
            StrongToastBridge.capability("controlCenter", true);
            XposedBridge.log(TAG + "Control Center state hook installed");
        } catch (Throwable e) { StrongToastBridge.capability("controlCenter", false); log("CC hook missing", e); }
    }

    private static boolean expired() { return saved == null || SystemClock.elapsedRealtime() >= expiresAt; }

    private static void scheduleRestore(long delay, String reason) {
        if (saved == null) return;
        if (!saved.getBoolean("s9_auto_restore", true) || replayFailures >= MAX_REPLAY_FAILURES) {
            cancelPending();
            return;
        }
        if (expired()) { clearCache("expiry"); return; }
        cancelPending();
        final int expectedGeneration = generation;
        pendingRestore = new Runnable() { @Override public void run() {
            pendingRestore = null;
            restoreIfNeeded(expectedGeneration);
        }};
        main.postDelayed(pendingRestore, delay);
        XposedBridge.log(TAG + "Restore scheduled: " + reason + " delayMs=" + delay);
    }

    private static boolean panelOpenNative() {
        // Read live SystemUI state where possible. The event booleans are only
        // fallback data: some HyperOS builds miss the final collapsed event.
        boolean shadeOpen = shadeExpanded;
        boolean ccOpen = ccExpanded;
        Object control = toastControl != null ? toastControl : StrongToastBridge.controller();
        if (control == null) return shadeOpen || ccOpen;
        try {
            Object cc = XposedHelpers.getObjectField(control, "mControlPanelWindowManager");
            if (cc != null) ccOpen = XposedHelpers.getBooleanField(cc, "isExpand");
        } catch (Throwable ignored) { }
        try {
            Object central = XposedHelpers.getObjectField(control, "mCentralSurfaces");
            if (central != null) {
                // The panel's expanded fraction is 0 when completely collapsed.
                // This is more reliable than stale mPanelExpanded / event flags.
                Object shade = null;
                try { shade = XposedHelpers.getObjectField(central, "mShadeSurface"); }
                catch (Throwable ignored) { }
                if (shade != null) {
                    try {
                        float fraction = XposedHelpers.getFloatField(shade, "mExpandedFraction");
                        boolean tracking = false;
                        try { tracking = XposedHelpers.getBooleanField(shade, "mTracking"); }
                        catch (Throwable ignored) { }
                        shadeOpen = fraction > 0.001f || tracking;
                    } catch (Throwable ignored) {
                        shadeOpen = XposedHelpers.getBooleanField(central, "mPanelExpanded");
                    }
                } else {
                    shadeOpen = XposedHelpers.getBooleanField(central, "mPanelExpanded");
                }
            }
        } catch (Throwable ignored) { }
        try {
            Object keyguard = XposedHelpers.getObjectField(control, "mKeyguardStateController");
            if (keyguard != null && XposedHelpers.getBooleanField(keyguard, "mShowing")) return true;
        } catch (Throwable ignored) { }
        return shadeOpen || ccOpen;
    }

    private static boolean nativeToastVisible() {
        if (toastControl == null) return false;
        try {
            Object toast = XposedHelpers.getObjectField(toastControl, "mMiuiStrongToastCallBack");
            return toast instanceof View && ((View)toast).isAttachedToWindow()
                && XposedHelpers.getBooleanField(toast, "mStrongToastShow");
        } catch (Throwable ignored) { return false; }
    }

    private static void restoreIfNeeded(int expectedGeneration) {
        if (saved == null || toastControl == null || generation != expectedGeneration ||
            !saved.getBoolean("s9_auto_restore", true) || replayFailures >= MAX_REPLAY_FAILURES) return;
        long remainingMs = expiresAt - SystemClock.elapsedRealtime();
        if (remainingMs < MIN_REMAINING_MS) { clearCache("duration elapsed"); return; }
        if (panelOpenNative()) {
            XposedBridge.log(TAG + "Restore deferred: panel/keyguard open; recheck in " + PANEL_RECHECK_MS + "ms");
            // Critical V0.4 fix: never abandon a hidden toast when the user
            // keeps the Notification Panel open for 10 seconds or longer.
            // This is a main-thread Handler, NOT a shell/su/root poll.
            scheduleRestore(PANEL_RECHECK_MS, "panel still open; await native collapse");
            return;
        }
        if (nativeToastVisible()) {
            nativeDismissed = false;
            StrongToastBridge.onVisibilityChanged();
            XposedBridge.log(TAG + "Native toast still shown: no duplicate");
            return;
        }
        if (!nativeDismissed) {
            XposedBridge.log(TAG + "No native dismiss event; native view absent, restoring on collapse");
        }
        try {
            Bundle replayRequest = new Bundle(saved);
            replayRequest.putLong("duration", remainingMs);
            replaying = true;
            // All of this runs within com.android.systemui; no root command needed.
            XposedHelpers.callMethod(toastControl, "showCustomStrongToast", replayRequest);
            nativeDismissed = false;
            replayFailures = 0;
            StrongToastBridge.onVisibilityChanged();
            XposedBridge.log(TAG + "Restore sent. RemainingMs=" + remainingMs);
            StrongToastEffects.resume(toastControl);
        } catch (Throwable e) {
            log("restore failed", e);
            replayFailures++;
            StrongToastBridge.setError("Native replay failed (" + replayFailures + "/" + MAX_REPLAY_FAILURES + ")");
            if (replayFailures < MAX_REPLAY_FAILURES)
                scheduleRestore(Math.min(12_000L, PANEL_RECHECK_MS << (replayFailures - 1)),
                    "retry failed native replay");
            StrongToastBridge.onVisibilityChanged();
        }
        finally { replaying = false; }
    }

    private static void cancelPending() {
        if (pendingRestore != null && main != null) {
            main.removeCallbacks(pendingRestore);
            pendingRestore = null;
        }
    }

    private static void clearCache(String reason) {
        cancelPending();
        if (finalExpiry != null && main != null) main.removeCallbacks(finalExpiry);
        finalExpiry = null;
        StrongToastEffects.stop();
        saved = null;
        toastControl = null;
        expiresAt = 0L;
        countdownEnd = 0L;
        countdownDuration = 0L;
        replayFailures = 0;
        nativeDismissed = false;
        generation++;
        StrongToastBridge.onCleared();
        XposedBridge.log(TAG + "Cache cleared: " + reason);
    }

    private static void scheduleFinalExpiry() {
        if (finalExpiry != null) main.removeCallbacks(finalExpiry);
        final int expected = generation;
        finalExpiry = new Runnable() { @Override public void run() {
            if (saved == null || generation != expected) return;
            if (expired()) clearCache("independent final expiry");
            else scheduleFinalExpiry();
        }};
        main.postDelayed(finalExpiry, Math.max(1L, expiresAt - SystemClock.elapsedRealtime()));
    }

    /** Main-thread bridge access; snapshots never infer visibility from a native call. */
    static Bundle snapshotState() {
        checkElapsedDeadline();
        Bundle state = new Bundle();
        boolean active = saved != null && !expired();
        state.putBoolean("active", active);
        state.putBoolean("visible", false);
        state.putBoolean("visibilityKnown", false);
        Object controller = toastControl != null ? toastControl : StrongToastBridge.controller();
        if (controller != null) {
            try {
                Object toast = XposedHelpers.getObjectField(controller, "mMiuiStrongToastCallBack");
                if (toast instanceof View) {
                    boolean shown = XposedHelpers.getBooleanField(toast, "mStrongToastShow");
                    state.putBoolean("visibilityKnown", true);
                    state.putBoolean("visible", ((View) toast).isAttachedToWindow() && shown);
                    StrongToastBridge.capability("nativeVisibility", true);
                } else if (toast == null) {
                    state.putBoolean("visibilityKnown", true);
                }
            } catch (Throwable error) { StrongToastBridge.capability("nativeVisibility", false); }
        }
        state.putBoolean("panelOpen", panelOpenNative());
        state.putBoolean("restoring", pendingRestore != null);
        state.putLong("expiresAtElapsed", active ? expiresAt : 0L);
        state.putLong("countdownEndElapsed", active ? countdownEnd : 0L);
        state.putLong("countdownDurationMillis", active ? countdownDuration : 0L);
        state.putBoolean("alerting", active && "timer".equals(saved.getString("s9_mode", "")) &&
            SystemClock.elapsedRealtime() >= countdownEnd);
        state.putString("mode", "SOLID");
        state.putString("style", "NORMAL");
        state.putString("text", "");
        state.putString("colorHex", "#FF0BFC03");
        state.putString("icon", "APP");
        if (active) {
            String mode = saved.getString("s9_mode", "");
            state.putString("mode", "timer".equals(mode) ? "TIMER" : "rgb".equals(mode) ? "RGB" : "SOLID");
            String style = saved.getString("s9_style", "normal").toUpperCase(Locale.ROOT);
            state.putString("style", "BOLD".equals(style) || "ITALIC".equals(style) ? style : "NORMAL");
            state.putString("icon", "com.sevtinge.hyperceiler".equals(saved.getString("package_name")) ? "HYPERCEILER" : "APP");
            try {
                JSONObject json = new JSONObject(saved.getString("param", "{}"));
                JSONObject left = json.optJSONObject("left");
                JSONObject text = left == null ? null : left.optJSONObject("textParams");
                if (text != null) {
                    state.putString("text", StrongToastBridge.boundedText(text.optString("text", "")));
                    state.putString("colorHex", String.format(Locale.ROOT, "#%08X", text.optInt("textColor", 0xFF0BFC03)));
                }
            } catch (Throwable ignored) { }
            for (String option : new String[]{"auto_restore", "vibration", "shake", "experimental_effects", "reduced_motion"})
                state.putBoolean(optionKey(option), saved.getBoolean("s9_" + option, !"reduced_motion".equals(option)));
        }
        return state;
    }

    private static String optionKey(String option) {
        if ("auto_restore".equals(option)) return "autoRestore";
        if ("experimental_effects".equals(option)) return "experimentalEffects";
        if ("reduced_motion".equals(option)) return "reducedMotion";
        return option;
    }

    static String activeRequestId() { return saved == null ? "" : saved.getString("s9_request_id", ""); }

    static void checkElapsedDeadline() {
        if (saved == null || !expired()) return;
        Object control = toastControl != null ? toastControl : StrongToastBridge.controller();
        try { stopFromBridge(control); }
        catch (Throwable error) { log("elapsed deadline native hide unavailable", error); }
    }

    static Bundle currentRequestCopy() { return saved == null ? null : new Bundle(saved); }
    static long originalCountdownEndElapsed() { return countdownEnd; }
    static long originalCountdownDurationMillis() { return countdownDuration; }

    static void updateSettings(Bundle settings) {
        if (saved != null && !settings.getBoolean("experimentalEffects", true) &&
            ("rgb".equals(saved.getString("s9_mode")) || "timer".equals(saved.getString("s9_mode")))) {
            stopFromBridge(StrongToastBridge.controller()); return;
        }
        if (saved != null) {
            for (String option : new String[]{"auto_restore", "vibration", "shake", "experimental_effects", "reduced_motion"})
                saved.putBoolean("s9_" + option, settings.getBoolean(optionKey(option), !"reduced_motion".equals(option)));
            StrongToastEffects.updateOptions(new Bundle(saved));
            if (!saved.getBoolean("s9_auto_restore", true)) cancelPending();
            else if (nativeDismissed && !nativeToastVisible()) scheduleRestore(PANEL_RECHECK_MS, "auto restore enabled");
        }
        StrongToastBridge.publish();
    }

    static void stopFromBridge(Object controller) {
        clearCache("authenticated stop command");
        if (controller == null) return;
        Bundle hide = new Bundle();
        hide.putString("status_bar_strong_toast", "hide_strong_toast");
        XposedHelpers.callMethod(controller, "setStatus", 1, "strong_toast_action", hide);
    }

    private static void log(String msg, Throwable e) {
        XposedBridge.log(TAG + msg + ": " + e);
    }
}
