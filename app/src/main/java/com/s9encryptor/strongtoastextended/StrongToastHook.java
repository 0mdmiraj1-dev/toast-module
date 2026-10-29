package com.s9encryptor.strongtoastextended;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperOS 1 / Android 13 LSPosed SystemUI-only module (V0.6).
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

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam param) {
        if (!SYSUI.equals(param.packageName)) return;
        main = new Handler(Looper.getMainLooper());
        installNativeWatchdog(param.classLoader);
        installToastCapture(param.classLoader);
        installNativeHideEvents(param.classLoader);
        installShadeEvents(param.classLoader);
        installControlCenterEvents(param.classLoader);
        XposedBridge.log(TAG + "V0.6 ready (native rainbow spans + styles + timer expiry alert)");
    }

    private static void onMain(Runnable action) {
        if (main == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    private static void installNativeWatchdog(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.toast.MIUIStrongToast", cl,
                "onAttachedToWindow", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Handler handler = (Handler) XposedHelpers.getObjectField(p.thisObject, "mLastHandler");
                            Runnable hide = (Runnable) XposedHelpers.getObjectField(p.thisObject, "mHideStrongToastRunnable");
                            if (handler != null && hide != null) {
                                handler.removeCallbacks(hide);
                                handler.postDelayed(hide, WATCHDOG_MS);
                            }
                        } catch (Throwable e) { log("watchdog error", e); }
                    }
                });
            XposedBridge.log(TAG + "65-minute watchdog hook installed");
        } catch (Throwable e) { log("watchdog hook missing", e); }
    }

    private static void installToastCapture(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.toast.MIUIStrongToastControl", cl,
                "showCustomStrongToast", Bundle.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(final MethodHookParam p) {
                        if (replaying || !(p.args[0] instanceof Bundle)) return;
                        final Bundle request = new Bundle((Bundle) p.args[0]);
                        final Object control = p.thisObject;
                        onMain(new Runnable() {
                            @Override public void run() {
                                String category = request.getString("strong_toast_category", "");
                                String pkg = request.getString("package_name", "");
                                long duration = request.getLong("duration", 0L);
                                // Do not replay a different app's old toast over a new request.
                                if (saved != null) clearCache("replaced by another request");
                                String effect = request.getString("s9_mode", "");
                                boolean effectMode = "rgb".equals(effect) || "timer".equals(effect) || "styled".equals(effect);
                                if (!"text_bitmap".equals(category) || pkg.isEmpty() ||
                                    duration < (effectMode ? 1_000L : MIN_STICKY_MS + 1L) ||
                                    duration > MAX_STICKY_MS) return;
                                saved = new Bundle(request);
                                toastControl = control;
                                expiresAt = SystemClock.elapsedRealtime() + duration;
                                nativeDismissed = false;
                                generation++;
                                XposedBridge.log(TAG + "Captured toast durationMs=" + duration + " effect=" + effect);
                                StrongToastEffects.start(effect, request.getString("s9_style", "normal"),
                                    request.getLong("s9_countdown_ms", duration), control, main, expiresAt);
                            }
                        });
                    }
                });
            XposedBridge.log(TAG + "Long-toast capture hook installed");
        } catch (Throwable e) { log("capture hook missing", e); }

        try {
            // An explicit status-bar 'hide' command must cancel automatic replay.
            XposedHelpers.findAndHookMethod("com.android.systemui.toast.MIUIStrongToastControl", cl,
                "setStatus", int.class, String.class, Bundle.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!"strong_toast_action".equals(p.args[1]) ||
                            !(p.args[2] instanceof Bundle)) return;
                        String action = ((Bundle) p.args[2]).getString("status_bar_strong_toast", "");
                        if ("hide_strong_toast".equals(action)) {
                            onMain(new Runnable() { @Override public void run() {
                                clearCache("explicit native hide command");
                            }});
                        }
                    }
                });
        } catch (Throwable e) { log("native hide action hook missing", e); }
    }

    private static void installNativeHideEvents(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.toast.MIUIStrongToast", cl,
                "hideStrongToast", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(final MethodHookParam p) {
                        markHiddenIfCurrent(p.thisObject, "hideStrongToast");
                    }
                });
            XposedHelpers.findAndHookMethod("com.android.systemui.toast.MIUIStrongToast", cl,
                "onComplete", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(final MethodHookParam p) {
                        markHiddenIfCurrent(p.thisObject, "onComplete");
                    }
                });
            XposedBridge.log(TAG + "Native hide/onComplete hooks installed");
        } catch (Throwable e) { log("native hide hook missing", e); }
    }

    private static void markHiddenIfCurrent(final Object toast, final String reason) {
        onMain(new Runnable() { @Override public void run() {
            if (saved == null || toastControl == null || replaying) return;
            if (!currentNativeToastIs(toast)) return;
            if (expired()) { clearCache("duration complete"); return; }
            nativeDismissed = true;
            StrongToastEffects.pause();
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
                            if (saved != null && (nativeDismissed || !nativeToastVisible())) {
                                scheduleRestore(shadeExpanded ? PANEL_RECHECK_MS : COLLAPSE_RESTORE_DELAY_MS,
                                    shadeExpanded ? "shade expanded (keep recovery armed)" : "shade collapsed");
                            }
                        }});
                    }
                });
            XposedBridge.log(TAG + "Shade state hook installed");
        } catch (Throwable e) { log("shade state hook missing", e); }

        try {
            // Additional ROM callback for full shade expanded/collapsed state.
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.phone.CentralSurfacesImpl", cl,
                "onShadeExpansionFullyChanged", Boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(final MethodHookParam p) {
                        final boolean expanded = Boolean.TRUE.equals(p.args[0]);
                        onMain(new Runnable() { @Override public void run() {
                            shadeExpanded = expanded;
                            if (saved != null && (nativeDismissed || !nativeToastVisible())) {
                                scheduleRestore(expanded ? PANEL_RECHECK_MS : COLLAPSE_RESTORE_DELAY_MS,
                                    expanded ? "shade fully expanded (keep recovery armed)" : "shade fully closed");
                            }
                        }});
                    }
                });
            XposedBridge.log(TAG + "CentralSurfaces shade hook installed");
        } catch (Throwable e) { log("CentralSurfaces hook missing", e); }

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
            XposedBridge.log(TAG + "Shade onClosingFinished hook installed");
        } catch (Throwable e) { log("Shade onClosingFinished hook missing", e); }
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
                            if (saved != null && (nativeDismissed || !nativeToastVisible())) {
                                scheduleRestore(expanded ? PANEL_RECHECK_MS : COLLAPSE_RESTORE_DELAY_MS,
                                    expanded ? "control center expanded (keep recovery armed)" : "control center closed");
                            }
                        }});
                    }
                });
            XposedBridge.log(TAG + "Control Center state hook installed");
        } catch (Throwable e) { log("CC hook missing", e); }
    }

    private static boolean expired() { return saved == null || SystemClock.elapsedRealtime() >= expiresAt; }

    private static void scheduleRestore(long delay, String reason) {
        if (saved == null) return;
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
        if (toastControl == null) return shadeOpen || ccOpen;
        try {
            Object cc = XposedHelpers.getObjectField(toastControl, "mControlPanelWindowManager");
            if (cc != null) ccOpen = XposedHelpers.getBooleanField(cc, "isExpand");
        } catch (Throwable ignored) { }
        try {
            Object central = XposedHelpers.getObjectField(toastControl, "mCentralSurfaces");
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
            Object keyguard = XposedHelpers.getObjectField(toastControl, "mKeyguardStateController");
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
        if (saved == null || toastControl == null || generation != expectedGeneration) return;
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
            XposedBridge.log(TAG + "Restore sent. RemainingMs=" + remainingMs);
            StrongToastEffects.resume(toastControl);
        } catch (Throwable e) {
            log("restore failed", e);
            // A transient SystemUI race must not permanently abandon recovery.
            scheduleRestore(PANEL_RECHECK_MS, "retry failed native replay");
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
        StrongToastEffects.stop();
        saved = null;
        toastControl = null;
        expiresAt = 0L;
        nativeDismissed = false;
        generation++;
        XposedBridge.log(TAG + "Cache cleared: " + reason);
    }

    private static void log(String msg, Throwable e) {
        XposedBridge.log(TAG + msg + ": " + e);
    }
}
