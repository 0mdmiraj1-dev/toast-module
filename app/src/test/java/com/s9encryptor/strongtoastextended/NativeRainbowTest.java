package com.s9encryptor.strongtoastextended;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpanWatcher;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.view.View;
import android.widget.TextView;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE,
    shadows={RecoveryRegressionTest.Access.class, RecoveryRegressionTest.Logs.class},
    instrumentedPackages={"de.robv.android.xposed"})
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeRainbowTest {
    TextView text;
    RecoveryRegressionTest.FakeControl control;
    @Before public void setup() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        RecoveryRegressionTest.FakeToast toast = new RecoveryRegressionTest.FakeToast(activity);
        control = new RecoveryRegressionTest.FakeControl(toast);
        toast.mStrongToastShow = true;
        text = toast.mLeftTextView;
        text.setText("My name is S9_Encryptor");
        activity.setContentView(toast);
        toast.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY));
        toast.layout(0, 0, 400, 64);
    }
    @After public void cleanup() { StrongToastEffects.stop(); }
    private void start(String style) {
        StrongToastEffects.start("rgb", style, 60000, control,
            new Handler(Looper.getMainLooper()), SystemClock.elapsedRealtime()+60000);
        tick(200);
        assertTrue(text.getText() instanceof Spannable);
    }
    private void tick(long millis) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }
    private CharacterStyle firstColor() {
        for (CharacterStyle span : ((Spannable)text.getText()).getSpans(0, text.length(), CharacterStyle.class))
            if (span.getClass().getSimpleName().equals("RainbowSpan")) return span;
        throw new AssertionError("Missing native rainbow span");
    }
    private int color(CharacterStyle span) {
        TextPaint paint = new TextPaint(); span.updateDrawState(paint); return paint.getColor();
    }
    @Test public void animatedSpansNotifyTextCacheWithoutReplacingTextForEveryFont() {
        for (String style : new String[]{"normal", "bold", "italic"}) {
            start(style);
            Spannable buffer = (Spannable) text.getText();
            Observer observer = new Observer();
            buffer.setSpan(observer, 0, buffer.length(), 18);
            text.addTextChangedListener(observer);
            CharacterStyle span = firstColor(); int before = color(span);
            tick(700);
            assertSame(buffer, text.getText());
            assertNotEquals(before, color(span));
            assertTrue("Text display cache must receive appearance changes", observer.spanChanges > 0);
            assertEquals("Frames must not restart text/marquee", 0, observer.textChanges);
            int expected = style.equals("bold") ? Typeface.BOLD : style.equals("italic") ? Typeface.ITALIC : Typeface.NORMAL;
            assertEquals(expected, text.getTypeface().getStyle());
            text.removeTextChangedListener(observer); buffer.removeSpan(observer);
            StrongToastEffects.stop();
            assertEquals(0, buffer.getSpans(0, buffer.length(), CharacterStyle.class).length);
        }
    }
    @Test public void nativeTextReplacementReinstallsRainbowOnTheSameView() {
        start("bold");
        CharacterStyle old = firstColor();
        text.setText("New native content 😀");
        tick(200);
        assertEquals("New native content 😀", text.getText().toString());
        assertNotSame(old, firstColor());
        int before = color(firstColor()); tick(700);
        assertNotEquals(before, color(firstColor()));
    }
    @Test public void reducedMotionAndStopCancelFrameNotifications() {
        start("normal");
        Bundle options = new Bundle(); options.putBoolean("s9_reduced_motion", true);
        StrongToastEffects.updateOptions(options); tick(10);
        Spannable buffer = (Spannable)text.getText(); Observer observer = new Observer();
        buffer.setSpan(observer, 0, buffer.length(), 18);
        int before = color(firstColor()); tick(700);
        assertEquals(before, color(firstColor())); assertEquals(0, observer.spanChanges);
        options.putBoolean("s9_reduced_motion", false);
        StrongToastEffects.updateOptions(options); tick(300);
        assertTrue(observer.spanChanges > 0);
        StrongToastEffects.stop(); observer.spanChanges=0; tick(700);
        assertEquals(0, observer.spanChanges);
    }
    private static final class Observer implements SpanWatcher, TextWatcher {
        int spanChanges, textChanges;
        public void onSpanAdded(Spannable s,Object what,int start,int end) { }
        public void onSpanRemoved(Spannable s,Object what,int start,int end) { }
        public void onSpanChanged(Spannable s,Object what,int os,int oe,int ns,int ne) { spanChanges++; }
        public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
        public void onTextChanged(CharSequence s,int start,int before,int count) { textChanges++; }
        public void afterTextChanged(Editable text) { }
    }
}
