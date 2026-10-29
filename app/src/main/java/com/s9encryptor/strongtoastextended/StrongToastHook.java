package com.s9encryptor.strongtoastextended;

import android.os.Handler;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * For Android 13 / HyperOS 1 ROMs which implement:
 * MIUIStrongToast#onAttachedToWindow() -> mLastHandler.postDelayed(mHideStrongToastRunnable, 30000)
 *
 * This hook extends only that independent 30s safety timer. It does not change
 * the actual toast duration in the bundle; patch your client to request that.
 * It does not stop SystemUI from dismissing the view on a panel gesture.
 */
public final class StrongToastHook implements IXposedHookLoadPackage {
    // 65 minutes, intentionally a little beyond the 60 min CLI maximum.
    // A 60-minute toast should still be dismissed by the normal duration handler.
    private static final long SAFETY_TIMEOUT_MS = 65L * 60L * 1000L;
    private static final String TAG = "[StrongToastExtended] ";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName)) return;
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.toast.MIUIStrongToast",
                    param.classLoader,
                    "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam call) {
                            try {
                                Object view = call.thisObject;
                                Handler handler = (Handler) XposedHelpers.getObjectField(
                                        view, "mLastHandler");
                                Runnable hide = (Runnable) XposedHelpers.getObjectField(
                                        view, "mHideStrongToastRunnable");
                                if (handler == null || hide == null) return;
                                // Cancel the native 30,000 ms watchdog and reschedule.
                                handler.removeCallbacks(hide);
                                handler.postDelayed(hide, SAFETY_TIMEOUT_MS);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "Failed to reschedule watchdog: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "Hook registered: MIUIStrongToast.onAttachedToWindow");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Class/method absent or ROM incompatible: " + t);
        }
    }
}
