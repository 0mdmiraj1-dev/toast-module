package com.s9encryptor.strongtoastextended;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeTextLayoutTest {
    private static class Fixture {
        final RelativeLayout root, left;
        final TextView text;
        final View icon;
        final int originalWidth;
        final NativeTextLayout.Binding binding;
        Fixture(String label, int style, boolean rgb, boolean longText) {
            Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
            root = new RelativeLayout(activity); left = new RelativeLayout(activity);
            text = new TextView(activity); icon = new View(activity);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22);
            text.setSingleLine(true);
            text.setEllipsize(longText ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
            text.setSelected(true);
            text.setText(label);
            originalWidth = Math.max(40, (int)text.getPaint().measureText(label) - 3);
            text.setMaxWidth(originalWidth);
            left.setId(View.generateViewId()); icon.setId(View.generateViewId());
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(originalWidth + 8, 48);
            lp.leftMargin = 12; root.addView(left, lp);
            left.addView(text, new RelativeLayout.LayoutParams(originalWidth, 48));
            RelativeLayout.LayoutParams ip = new RelativeLayout.LayoutParams(28, 32);
            ip.addRule(RelativeLayout.ALIGN_PARENT_RIGHT); ip.rightMargin = 12;
            root.addView(icon, ip);
            activity.setContentView(root);
            layout(400);
            binding = NativeTextLayout.bind(text, root, left, icon);
            text.setTypeface(Typeface.DEFAULT, style);
            if (rgb) {
                SpannableString colors = new SpannableString(label);
                for (int i=0; i<label.length(); i++) colors.setSpan(new ForegroundColorSpan(0xFF00FF00 + i), i, i+1, 33);
                text.setText(colors);
            }
            settle(400);
        }
        void layout(int width) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
            root.layout(0,0,width,48);
        }
        void settle(int width) {
            for (int i=0; i<5; i++) { layout(width); binding.reconcileNow(); }
            layout(width);
        }
        void assertFits(String original) {
            assertEquals(original, text.getText().toString());
            assertNull(text.getEllipsize());
            assertEquals(22f, text.getTextSize(), 0.01f);
            assertTrue("adequate ink/advance allocation", text.getWidth() + .01f >= binding.requiredPixels());
            assertTrue("must not overlap right icon", left.getLeft()+text.getLeft()+text.getWidth() <= icon.getLeft()-2);
            assertEquals(360, icon.getLeft());
        }
    }
    @Test public void shortStringsDoNotRequireTrailingWhitespaceForAnyFont() {
        for(String label:new String[]{"S9_Encryptor","S9_Encryptor ","@S9_Encryptor","S9 😀","Remaining 29:59"})
            for(int style:new int[]{Typeface.NORMAL,Typeface.BOLD,Typeface.ITALIC}) {
                Fixture f=new Fixture(label,style,false,false); f.assertFits(label); f.binding.close();
            }
    }
    @Test public void rainbowSpansSurviveWidthFixWithoutShrinking() {
        for(int style:new int[]{Typeface.NORMAL,Typeface.BOLD,Typeface.ITALIC}) {
            Fixture f=new Fixture("S9_Encryptor",style,true,false);
            f.assertFits("S9_Encryptor"); assertTrue(f.text.getText() instanceof android.text.Spanned);
            f.binding.close();
        }
    }
    @Test public void genuinelyLongTextKeepsNativeMarquee() {
        Fixture f=new Fixture("Long scrolling status 😀 ".repeat(30),Typeface.BOLD,true,true);
        assertEquals(TextUtils.TruncateAt.MARQUEE,f.text.getEllipsize());
        assertEquals(f.originalWidth,f.text.getLayoutParams().width);
        assertEquals(22f,f.text.getTextSize(),.01f); f.binding.close();
    }
    @Test public void reusedViewGetsOriginalFontAndLayoutBack() {
        Fixture f=new Fixture("S9_Encryptor",Typeface.BOLD,false,false);
        f.binding.close();
        assertEquals(f.originalWidth,f.text.getLayoutParams().width);
        assertEquals(TextUtils.TruncateAt.END,f.text.getEllipsize());
        assertFalse(f.text.getTypeface().isBold()); assertEquals(22f,f.text.getTextSize(),.01f);
        assertTrue(f.binding.isClosed());
    }
    @Test public void textAndScreenChangesRecalculateWithoutLayoutLoop() {
        Fixture f=new Fixture("S9_Encryptor",Typeface.NORMAL,false,false);
        f.text.setText("@S9_Encryptor"); f.settle(400); f.assertFits("@S9_Encryptor");
        f.settle(340);
        assertEquals(300,f.icon.getLeft());
        assertTrue(f.left.getLeft()+f.text.getWidth()<=f.icon.getLeft()-2);
        int before=f.binding.reconciliationCount();
        for(int i=0;i<50;i++)f.binding.reconcileNow();
        assertTrue(f.binding.reconciliationCount()-before<3); f.binding.close();
    }
}
