package com.s9encryptor.strongtoastextended;

import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.os.Handler;
import android.os.SystemClock;
import android.widget.TextView;
import android.view.View;
import java.util.Locale;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs entirely inside the existing com.android.systemui LSPosed process.
 * No su, no Termux polling, no text re-creation on every timer tick.
 * This class is intentionally independent of the V0.4 restoration state machine.
 */
final class StrongToastEffects {
    private static final String TAG = "[StrongToastEffects] ";
    private static final long RGB_FRAME_MS = 65L;  // 15 FPS; affordable for SystemUI
    private static final long TIMER_FRAME_MS = 180L; // 5.5 FPS: interpolate hue smoothly
    private static final int[] RAINBOW = new int[] {
        0xFFFF4141, 0xFFFFA632, 0xFFFFFF50, 0xFF54FF75,
        0xFF44E8FF, 0xFF4786FF, 0xFFC556FF, 0xFFFF4141
    };
    private static Session session;
    private static int nextId = 1;

    private StrongToastEffects() {}

    static void start(String mode, Object control, Handler handler, long deadlineElapsed) {
        stop();
        if (!"rgb".equals(mode) && !"timer".equals(mode)) return;
        final Session s = new Session(mode, control, handler, deadlineElapsed, nextId++);
        session = s;
        schedule(s, 140L);
        XposedBridge.log(TAG + "Session started mode=" + mode + " deadline=" + deadlineElapsed);
    }

    static void pause() {
        Session s = session;
        if (s == null) return;
        s.paused = true;
        if (s.frame != null) s.handler.removeCallbacks(s.frame);
        s.frame = null;
        releaseShader(s);
    }

    static void resume(Object control) {
        Session s = session;
        if (s == null) return;
        s.control = control;
        s.paused = false;
        s.view = null; // Native Toast is a new TextView after replay.
        s.gradient = null;
        s.matrix = null;
        s.previousSeconds = -1;
        schedule(s, 220L);
    }

    static void stop() {
        Session s = session;
        session = null;
        if (s == null) return;
        s.paused = true;
        if (s.frame != null) s.handler.removeCallbacks(s.frame);
        s.frame = null;
        releaseShader(s);
    }

    private static final class Session {
        final String mode;
        Object control;
        final Handler handler;
        final long deadline;
        final long startTime;
        final long duration;
        final int id;
        TextView view;
        LinearGradient gradient;
        Matrix matrix;
        Runnable frame;
        int previousSeconds = -1;
        int previousColor = 0;
        boolean paused;
        Session(String mode, Object control, Handler handler, long deadline, int id) {
            this.mode = mode;
            this.control = control;
            this.handler = handler;
            this.deadline = deadline;
            this.startTime = SystemClock.elapsedRealtime();
            this.duration = Math.max(1L, deadline - this.startTime);
            this.id = id;
        }
    }

    private static void schedule(final Session s, long delayMs) {
        if (session != s || s.paused) return;
        if (s.frame != null) s.handler.removeCallbacks(s.frame);
        final Runnable tick = new Runnable() {
            @Override public void run() {
                s.frame = null;
                if (session != s || s.paused) return;
                try { frame(s); }
                catch (Throwable error) {
                    XposedBridge.log(TAG + "effect frame error: " + error);
                    // If the ROM's native TextView layout differs, don't crash SystemUI.
                    pause();
                }
            }
        };
        s.frame = tick;
        s.handler.postDelayed(tick, delayMs);
    }

    private static void frame(Session s) {
        long now = SystemClock.elapsedRealtime();
        long remaining = s.deadline - now;
        if (remaining <= 0L) { stop(); return; }
        if (!bindView(s)) {
            // Native dismiss is handled by V0.4 replay. Do not recreate here.
            schedule(s, 350L);
            return;
        }
        if ("rgb".equals(s.mode)) {
            drawMovingRainbow(s, now);
            schedule(s, RGB_FRAME_MS);
        } else {
            drawCountdown(s, remaining);
            schedule(s, TIMER_FRAME_MS);
        }
    }

    private static boolean bindView(Session s) {
        try {
            Object toast = XposedHelpers.getObjectField(s.control, "mMiuiStrongToastCallBack");
            if (!(toast instanceof View) || !((View) toast).isAttachedToWindow() ||
                !XposedHelpers.getBooleanField(toast, "mStrongToastShow")) return false;
            Object left = XposedHelpers.getObjectField(toast, "mLeftTextView");
            if (!(left instanceof TextView)) return false;
            TextView v = (TextView) left;
            if (s.view != v) {
                releaseShader(s);
                s.view = v;
                s.gradient = null;
                s.matrix = null;
                s.previousSeconds = -1;
                s.previousColor = 0;
                XposedBridge.log(TAG + "Bound " + s.mode + " to native TextView");
            }
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private static void drawMovingRainbow(Session s, long now) {
        TextView v = s.view;
        if (v == null) return;
        if (s.gradient == null) {
            // One color cycle over the visible status width; repeated in long texts.
            int width = Math.max(v.getWidth(), 260);
            s.gradient = new LinearGradient(0, 0, width, 0, RAINBOW, null, Shader.TileMode.REPEAT);
            s.matrix = new Matrix();
            v.getPaint().setShader(s.gradient);
        }
        float period = Math.max(260, v.getWidth());
        float phase = ((now - s.startTime) % 9_000L) / 9_000f;
        s.matrix.setTranslate(phase * period, 0f);
        s.gradient.setLocalMatrix(s.matrix);
        v.invalidate();
    }

    private static void drawCountdown(Session s, long remainingMs) {
        TextView v = s.view;
        if (v == null) return;
        // ceil: exactly 30m -> 30:00, one second later -> 29:59
        int seconds = (int) Math.max(0L, (remainingMs + 999L) / 1_000L);
        if (s.previousSeconds != seconds) {
            s.previousSeconds = seconds;
            int minutes = seconds / 60;
            int leftover = seconds % 60;
            v.setText(String.format(Locale.US, "Remaining %02d:%02d", minutes, leftover));
        }
        float r = Math.max(0f, Math.min(1f, remainingMs / (float) s.duration));
        float hue;
        if (r >= .35f) hue = 122f;
        else if (r > .33f) hue = smoothHue(r, .35f, .33f, 122f, 30f);
        else if (r >= .17f) hue = 30f;
        else if (r > .15f) hue = smoothHue(r, .17f, .15f, 30f, 0f);
        else hue = 0f;
        int color = Color.HSVToColor(new float[] { hue, 1f, 1f });
        if (s.previousColor != color) {
            s.previousColor = color;
            v.setTextColor(color);
        }
    }

    private static float smoothHue(float remainingRatio, float high, float low,
                                   float hueFrom, float hueTo) {
        float p = (high - remainingRatio) / (high - low);
        p = Math.max(0f, Math.min(1f, p));
        p = p * p * (3f - 2f * p);  // smoothstep
        return hueFrom + (hueTo - hueFrom) * p;
    }

    private static void releaseShader(Session s) {
        if (s.view != null && s.gradient != null) {
            try { s.view.getPaint().setShader(null); s.view.invalidate(); }
            catch (Throwable ignored) {}
        }
    }
}
