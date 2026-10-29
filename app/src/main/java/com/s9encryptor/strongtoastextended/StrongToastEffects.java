package com.s9encryptor.strongtoastextended;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.app.NotificationManager;
import android.os.Bundle;
import android.os.Build;
import android.os.VibrationAttributes;
import android.text.Spannable;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.TypedValue;
import android.os.Handler;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.SpannableString;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.view.View;
import android.widget.TextView;
import java.util.Locale;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** All visual updates happen on SystemUI's main Looper. No root polling. */
final class StrongToastEffects {
    private static final String TAG = "[StrongToastEffects] ";
    private static final long RGB_FRAME_MS = 95L;
    private static final long TIMER_FRAME_MS = 120L;
    private static final long ALERT_MS = 30_000L;
    private static final int MAX_RAINBOW_SEGMENTS = 90;
    private static Session session;
    private static int nextId = 1;

    private StrongToastEffects() {}

    static void start(String mode, String style, long countdownMs,
                      Object control, Handler handler, long deadlineElapsed) {
        stop();
        if (!"rgb".equals(mode) && !"timer".equals(mode) && !"styled".equals(mode)) return;
        Session s = new Session(mode, normalizeStyle(style), control, handler,
            deadlineElapsed, countdownMs, nextId++);
        session = s;
        applyOptions(s, StrongToastHook.currentRequestCopy());
        s.expiry = () -> { if (session == s) { StrongToastHook.checkElapsedDeadline(); stop(); } };
        handler.postDelayed(s.expiry, Math.max(1L, deadlineElapsed - SystemClock.elapsedRealtime()));
        if ("timer".equals(mode)) {
            s.alert = () -> { if (session == s && SystemClock.elapsedRealtime() < s.deadline) beginAlert(s); };
            handler.postDelayed(s.alert, Math.max(1L, s.countdownEnd - SystemClock.elapsedRealtime()));
        }
        schedule(s, 140L);
        XposedBridge.log(TAG + "started mode=" + mode + " style=" + s.style +
            " countdownMs=" + countdownMs + " expiry=" + deadlineElapsed);
    }

    static void pause() {
        Session s = session;
        if (s == null) return;
        s.paused = true;
        if (s.frame != null) s.handler.removeCallbacks(s.frame);
        s.frame = null;
        releaseNativeView(s);
        s.view = null;
        s.spans = null;
    }

    /** Handler delays use uptime; reconcile elapsed deadlines when the screen wakes. */
    static void onScreenWake() {
        Session s = session;
        if (s == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now >= s.deadline) { StrongToastHook.checkElapsedDeadline(); stop(); return; }
        if (s.expiry != null) {
            s.handler.removeCallbacks(s.expiry);
            s.handler.postDelayed(s.expiry, Math.max(1L, s.deadline - now));
        }
        if (s.alert != null) {
            s.handler.removeCallbacks(s.alert);
            if (!s.alertStarted && now < s.countdownEnd)
                s.handler.postDelayed(s.alert, Math.max(1L, s.countdownEnd - now));
        }
        if ("timer".equals(s.mode) && now >= s.countdownEnd && !s.alertStarted) beginAlert(s);
        if (!s.paused) schedule(s, 1L);
    }

    static void resume(Object control) {
        Session s = session;
        if (s == null) return;
        s.control = control;
        s.paused = false;
        releaseNativeView(s);
        s.view = null;
        s.spans = null;
        s.previousSeconds = -1;
        s.previousColor = 0;
        schedule(s, 200L);
    }

    static void stop() {
        Session s = session;
        session = null;
        if (s == null) return;
        s.paused = true;
        if (s.frame != null) s.handler.removeCallbacks(s.frame);
        if (s.alert != null) s.handler.removeCallbacks(s.alert);
        if (s.expiry != null) s.handler.removeCallbacks(s.expiry);
        s.frame = null;
        releaseNativeView(s);
        s.view = null;
        s.spans = null;
        stopOwnedVibration(s);
    }

    private static String normalizeStyle(String s) {
        if ("bold".equalsIgnoreCase(s)) return "bold";
        if ("italic".equalsIgnoreCase(s)) return "italic";
        return "normal";
    }

    private static final class Session {
        final String mode;
        final String style;
        Object control;
        final Handler handler;
        final long createdAt;
        final long deadline;
        final long countdownEnd;
        final long countdownDuration;
        final int id;
        TextView view;
        Runnable frame;
        Runnable alert, expiry;
        boolean reducedMotion, vibrationEnabled = true, shakeEnabled = true;
        TextView shakeView;
        float shakeOrigin;
        RainbowSpan[] spans;
        boolean rainbowFallback;
        float phase;
        int previousSeconds = -1;
        int previousColor;
        boolean paused;
        NativeTextLayout.Binding layout;
        boolean alertStarted;
        boolean vibrating;
        Vibrator vibrator;
        ObjectAnimator shake;

        Session(String mode, String style, Object control, Handler handler,
                long deadline, long countdownMs, int id) {
            this.mode = mode;
            this.style = style;
            this.control = control;
            this.handler = handler;
            this.createdAt = SystemClock.elapsedRealtime();
            this.deadline = deadline;
            this.countdownDuration = Math.max(1000L, countdownMs);
            long original = StrongToastHook.originalCountdownEndElapsed();
            this.countdownEnd = "timer".equals(mode) ? Math.min(deadline, original > 0L ? original : createdAt + this.countdownDuration) : deadline;
            this.id = id;
        }
    }

    // Mutable CharacterStyle: setText once, then invalidate each frame. This
    // avoids the GPU shader-local-matrix bug that left V0.5 stuck at red.
    private static final class RainbowSpan extends CharacterStyle implements UpdateAppearance {
        final Session owner;
        final float offset;
        RainbowSpan(Session owner, float offset) { this.owner = owner; this.offset = offset; }
        @Override public void updateDrawState(TextPaint paint) {
            float hue = (360f * (owner.phase + offset)) % 360f;
            if (hue < 0f) hue += 360f;
            paint.setShader(null);
            paint.setColor(Color.HSVToColor(new float[]{hue, .95f, 1f}));
        }
    }

    private static void schedule(final Session s, long delayMs) {
        if (session != s || s.paused) return;
        if (s.frame != null) s.handler.removeCallbacks(s.frame);
        Runnable tick = new Runnable() {
            @Override public void run() {
                s.frame = null;
                if (session != s || s.paused) return;
                try { frame(s); }
                catch (Throwable e) {
                    XposedBridge.log(TAG + "effect frame error (effects stopped safely): " + e);
                    pause();
                }
            }
        };
        s.frame = tick;
        s.handler.postDelayed(tick, delayMs);
    }

    private static void frame(Session s) {
        long now = SystemClock.elapsedRealtime();
        if (now >= s.deadline) { StrongToastHook.checkElapsedDeadline(); stop(); return; }
        if ("timer".equals(s.mode) && now >= s.countdownEnd && !s.alertStarted)
            beginAlert(s);
        if (!bindView(s)) {
            schedule(s, 350L); // Restorer owns re-creation, not effects.
            return;
        }
        if ("rgb".equals(s.mode)) {
            if (s.layout != null) s.layout.refresh();
            updateRainbow(s, now);
            if (!s.reducedMotion) schedule(s, RGB_FRAME_MS);
        } else if ("timer".equals(s.mode)) {
            if (s.alertStarted) {
                showTimeOver(s);
                schedule(s, 250L);
            } else {
                drawCountdown(s, s.countdownEnd - now);
                if (s.layout != null) s.layout.refresh();
                schedule(s, TIMER_FRAME_MS);
            }
        } else {
            // Styled mode normally has no animation. Retry fitting until layout
            // reports a non-zero width; no repeating root calls or view recreation.
            if (s.layout != null) s.layout.refresh();

        }
    }

    private static boolean bindView(Session s) {
        try {
            Object toast = XposedHelpers.getObjectField(s.control, "mMiuiStrongToastCallBack");
            if (!(toast instanceof View) || !((View) toast).isAttachedToWindow() ||
                !XposedHelpers.getBooleanField(toast, "mStrongToastShow")) return false;
            Object left = XposedHelpers.getObjectField(toast, "mLeftTextView");
            if (!(left instanceof TextView)) return false;
            TextView view = (TextView) left;
            if (s.view != view) {
                releaseNativeView(s);
                s.view = view;
                s.layout = prepareNativeLayout(toast);
                s.spans = null;
                s.rainbowFallback = false;
                s.previousSeconds = -1;
                s.previousColor = 0;
                setStyle(view, s.style);
                if ("rgb".equals(s.mode)) installRainbow(s);
                XposedBridge.log(TAG + "bound mode=" + s.mode + " style=" + s.style);
            }
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private static void setStyle(TextView view, String style) {
        int mode = Typeface.NORMAL;
        if ("bold".equals(style)) mode = Typeface.BOLD;
        else if ("italic".equals(style)) mode = Typeface.ITALIC;
        view.setTypeface(view.getTypeface(), mode);
    }

    static NativeTextLayout.Binding prepareNativeLayout(Object toast) {
        try {
            Object text = XposedHelpers.getObjectField(toast, "mLeftTextView");
            if (!(text instanceof TextView) || !(toast instanceof View)) return null;
            View left = null, right = null;
            try { left = (View)XposedHelpers.getObjectField(toast, "mRLLeft"); } catch (Throwable ignored) { }
            try { right = (View)XposedHelpers.getObjectField(toast, "mRLRight"); } catch (Throwable ignored) { }
            return NativeTextLayout.bind((TextView)text, (View)toast, left, right);
        } catch (Throwable ignored) { return null; }
    }

    private static void releaseLayout(Session session) {
        if (session.layout != null) { session.layout.close(); session.layout = null; }
    }

    private static void installRainbow(Session s) {
        TextView v = s.view;
        if (v == null) return;
        try {
            String value = v.getText().toString();
            int codepoints = value.codePointCount(0, value.length());
            if (codepoints == 0) return;
            int segments = Math.min(MAX_RAINBOW_SEGMENTS, codepoints);
            SpannableString colored = new SpannableString(value);
            RainbowSpan[] newSpans = new RainbowSpan[segments];
            for (int i = 0; i < segments; i++) {
                int from = value.offsetByCodePoints(0, (i * codepoints) / segments);
                int to = value.offsetByCodePoints(0, ((i + 1) * codepoints) / segments);
                if (to <= from) continue;
                RainbowSpan span = new RainbowSpan(s, (float)i / (float)segments);
                newSpans[i] = span;
                colored.setSpan(span, from, to, 33); // Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            }
            v.getPaint().setShader(null);
            v.setText(colored, TextView.BufferType.SPANNABLE);
            s.spans = newSpans;
            XposedBridge.log(TAG + "Rainbow spans installed count=" + segments);
        } catch (Throwable e) {
            s.rainbowFallback = true;
            XposedBridge.log(TAG + "Rainbow spans failed; hue-cycling fallback: " + e);
        }
    }

    private static void updateRainbow(Session s, long now) {
        if (s.view == null) return;
        s.phase = s.reducedMotion ? 0f : ((now - s.createdAt) % 6200L) / 6200f;
        if (s.rainbowFallback || s.spans == null) {
            // Visible fallback if the ROM rejects colored CharacterStyle spans.
            s.view.setTextColor(Color.HSVToColor(new float[]{360f * s.phase, 1f, 1f}));
        }
        s.view.invalidate();
    }

    private static void drawCountdown(Session s, long remainingMs) {
        if (s.view == null) return;
        int seconds = (int)Math.max(0L, (remainingMs + 999L) / 1000L);
        if (s.previousSeconds != seconds) {
            s.previousSeconds = seconds;
            s.view.setText(String.format(Locale.US, "Remaining %02d:%02d", seconds/60, seconds%60));
        }
        float fraction = Math.max(0f, Math.min(1f, remainingMs / (float)s.countdownDuration));
        // Smooth threshold transitions: green -> orange near 33%, orange -> red near 15%.
        float hue;
        if (fraction >= .40f) hue = 120f;
        else if (fraction > .33f) hue = smoothHue(fraction, .40f, .33f, 120f, 30f);
        else if (fraction >= .21f) hue = 30f;
        else if (fraction > .15f) hue = smoothHue(fraction, .21f, .15f, 30f, 0f);
        else hue = 0f;
        int color = Color.HSVToColor(new float[]{hue, 1f, 1f});
        if (color != s.previousColor) { s.previousColor = color; s.view.setTextColor(color); }
    }

    private static float smoothHue(float ratio, float high, float low, float from, float to) {
        float t = Math.max(0f, Math.min(1f, (high - ratio)/(high - low)));
        t = t*t*(3f-2f*t);
        return from + (to-from)*t;
    }

    private static void beginAlert(Session s) {
        if (s.alertStarted) return;
        s.alertStarted = true;
        s.previousSeconds = -1;
        XposedBridge.log(TAG + "Timer expired. Start Time over! + 30s vibration");
        if (!s.vibrationEnabled) return;
        try {
            Object toast = XposedHelpers.getObjectField(s.control, "mMiuiStrongToastCallBack");
            Context context = toast instanceof View ? ((View)toast).getContext() : null;
            if (context != null) {
                NotificationManager notifications = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (notifications != null && notifications.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) {
                    XposedBridge.log(TAG + "Vibration suppressed by device DND policy"); return;
                }
                Vibrator vib = (Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);
                if (vib != null && vib.hasVibrator()) {
                    s.vibrator = vib;
                    long time = Math.min(ALERT_MS, s.deadline - SystemClock.elapsedRealtime());
                    if (time <= 0L) return;
                    VibrationEffect effect = VibrationEffect.createOneShot(time, VibrationEffect.DEFAULT_AMPLITUDE);
                    if (Build.VERSION.SDK_INT >= 33) vib.vibrate(effect,
                        new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_NOTIFICATION).build());
                    else vib.vibrate(effect);
                    s.vibrating = true;
                }
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + "Vibration unavailable (permission/DND/ROM): " + e);
        }
    }

    private static void showTimeOver(Session s) {
        TextView v = s.view;
        if (v == null) return;
        if (!"Time over!".contentEquals(v.getText())) {
            v.setText("Time over!");
            v.setTextColor(Color.RED);
            if (s.shakeEnabled && !s.reducedMotion) shake(v, s);
        }
    }

    private static void shake(TextView v, Session s) {
        cancelShake(s);
        try {
            s.shakeView = v;
            s.shakeOrigin = v.getTranslationX();
            float d = v.getResources().getDisplayMetrics().density;
            float x = 9f * d;
            s.shake = ObjectAnimator.ofFloat(v, View.TRANSLATION_X,
                s.shakeOrigin, s.shakeOrigin-x, s.shakeOrigin+x, s.shakeOrigin-x*.8f,
                s.shakeOrigin+x*.8f, s.shakeOrigin-x*.5f, s.shakeOrigin+x*.5f, s.shakeOrigin);
            s.shake.setDuration(1100L);
            s.shake.start();
        } catch (Throwable e) { XposedBridge.log(TAG + "shake unavailable: " + e); }
    }

    static void updateOptions(Bundle options) {
        Session s = session;
        if (s == null) return;
        applyOptions(s, options);
        if (!s.vibrationEnabled) stopOwnedVibration(s);
        if (!s.shakeEnabled || s.reducedMotion) cancelShake(s);
        if (!s.paused) schedule(s, 1L);
    }
    private static void applyOptions(Session s, Bundle options) {
        if (options == null) return;
        s.reducedMotion = options.getBoolean("s9_reduced_motion", false);
        s.vibrationEnabled = options.getBoolean("s9_vibration", true);
        s.shakeEnabled = options.getBoolean("s9_shake", true);
    }
    private static void cancelShake(Session s) {
        if (s.shake != null) { try { s.shake.cancel(); } catch (Throwable ignored) { } s.shake = null; }
        if (s.shakeView != null) { try { s.shakeView.setTranslationX(s.shakeOrigin); } catch (Throwable ignored) { } s.shakeView = null; }
    }
    private static void releaseNativeView(Session s) {
        cancelShake(s);
        if (s.view != null && s.view.getText() instanceof Spannable) {
            Spannable text = (Spannable)s.view.getText();
            for (RainbowSpan span : text.getSpans(0, text.length(), RainbowSpan.class))
                if (span.owner == s) text.removeSpan(span);
        }
        releaseLayout(s);
    }

    private static void stopOwnedVibration(Session s) {
        cancelShake(s);
        if (s.vibrating && s.vibrator != null) {
            try { s.vibrator.cancel(); } catch (Throwable ignored) {}
        }
        s.vibrating = false;
    }
}
