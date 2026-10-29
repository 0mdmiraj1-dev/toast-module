package com.s9encryptor.strongtoastextended;

import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Layout;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Repairs the native text allocation, without changing input text, font size or icon geometry. */
public final class NativeTextLayout {
    private static final WeakHashMap<TextView, WeakReference<Binding>> BINDINGS = new WeakHashMap<>();
    private NativeTextLayout() { }

    public static Binding bind(TextView text, View root, View left, View right) {
        WeakReference<Binding> reference = BINDINGS.get(text);
        Binding previous = reference == null ? null : reference.get();
        if (previous != null && !previous.closed && previous.root == root) return previous;
        if (previous != null) previous.close();
        Binding binding = new Binding(text, root, left, right);
        BINDINGS.put(text, new WeakReference<>(binding));
        binding.refresh();
        return binding;
    }

    public static final class Binding implements AutoCloseable {
        private final TextView text;
        private final View root, left, right;
        private final int originalWidth, originalLeftWidth, originalMin, originalMax, originalMinEms, originalMaxEms;
        private final int paddingLeft, paddingTop, paddingRight, paddingBottom;
        private final TextUtils.TruncateAt ellipsize;
        private final Typeface originalTypeface;
        private final float originalSize;
        private final boolean originalSelected;
        private final View.OnLayoutChangeListener layoutListener;
        private final View.OnAttachStateChangeListener attachListener;
        private final TextWatcher watcher;
        private boolean queued, closed, applying, verifyIcon;
        private long previousGeometry = Long.MIN_VALUE, appliedKey = Long.MIN_VALUE, blockedKey = Long.MIN_VALUE;
        private int stablePasses, retries, protectedIconLeft, protectedRootWidth;
        private float lastAvailable, lastRequired;
        private int reconciliations;
        private final Runnable reconcile = this::reconcile;

        private Binding(TextView text, View root, View left, View right) {
            this.text = text;
            this.root = root == null ? text : root;
            this.left = left;
            this.right = right;
            originalWidth = widthOf(text);
            originalLeftWidth = widthOf(left);
            originalMin = text.getMinWidth(); originalMax = text.getMaxWidth();
            originalMinEms = text.getMinEms(); originalMaxEms = text.getMaxEms();
            paddingLeft = text.getPaddingLeft(); paddingTop = text.getPaddingTop();
            paddingRight = text.getPaddingRight(); paddingBottom = text.getPaddingBottom();
            ellipsize = text.getEllipsize(); originalSelected = text.isSelected();
            originalTypeface = text.getTypeface(); originalSize = text.getTextSize();
            layoutListener = (v, l, t, r, b, ol, ot, or, ob) -> { if (!applying) refresh(); };
            watcher = new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                public void onTextChanged(CharSequence s, int start, int before, int count) { if (!applying) refresh(); }
                public void afterTextChanged(Editable value) { }
            };
            attachListener = new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View v) { refresh(); }
                public void onViewDetachedFromWindow(View v) { close(); }
            };
            text.addTextChangedListener(watcher);
            text.addOnAttachStateChangeListener(attachListener);
            this.root.addOnLayoutChangeListener(layoutListener);
            text.addOnLayoutChangeListener(layoutListener);
            if (left != null && left != this.root && left != text) left.addOnLayoutChangeListener(layoutListener);
            if (right != null) right.addOnLayoutChangeListener(layoutListener);
        }

        public void refresh() {
            if (closed || queued || applying) return;
            queued = true;
            text.postOnAnimation(reconcile);
        }

        /** Also exposed to deterministic Android view regression tests. */
        public void reconcileNow() { text.removeCallbacks(reconcile); queued = false; reconcile(); }
        public float availablePixels() { return lastAvailable; }
        public float requiredPixels() { return lastRequired; }
        public int reconciliationCount() { return reconciliations; }
        public boolean isClosed() { return closed; }

        private void reconcile() {
            queued = false;
            if (closed || applying) return;
            try {
                String value = text.getText().toString();
                if (value.isEmpty() || root.getWidth() <= 0 || text.getWidth() <= 0) {
                    if (++retries < 16) refresh();
                    return;
                }
                long key = externalKey(value);
                if (blockedKey == key) return;
                int textX = localX(text, root);
                int rootEnd = root.getWidth() - root.getPaddingRight();
                int iconX = right != null && right.getVisibility() != View.GONE && right.getWidth() > 0 ? localX(right, root) : rootEnd;
                if (verifyIcon) {
                    verifyIcon = false;
                    if (root.getWidth() == protectedRootWidth && Math.abs(iconX - protectedIconLeft) > 1) {
                        restoreGeometry(); blockedKey = key; return;
                    }
                }
                // LTR and the right-icon toast layout: if the icon is on the other side,
                // retain native layout rather than risking overlap on an unknown ROM.
                if (textX < 0 || iconX <= textX) return;
                float density = text.getResources().getDisplayMetrics().density;
                int gap = Math.max(2, (int)Math.ceil(2f * density));
                int boundary = Math.min(rootEnd, iconX - (right == null ? 0 : gap));
                float capacity = boundary - textX;
                if (capacity <= 0) return;
                long geometry = key * 31 + boundary;
                if (geometry != previousGeometry) { previousGeometry = geometry; stablePasses = 0; refresh(); return; }
                if (++stablePasses < 1) { refresh(); return; }
                if (appliedKey == geometry) return;
                retries = 0;
                float advance = text.getPaint().measureText(value);
                float styled = Layout.getDesiredWidth(text.getText(), text.getPaint());
                Rect ink = new Rect(); text.getPaint().getTextBounds(value, 0, value.length(), ink);
                int startOverhang = Math.max(0, -ink.left);
                int endOverhang = Math.max(0, (int)Math.ceil(ink.right - advance));
                int safety = Math.max(1, (int)Math.ceil(density));
                int compoundLeft = Math.max(0, text.getCompoundPaddingLeft() - text.getPaddingLeft());
                int compoundRight = Math.max(0, text.getCompoundPaddingRight() - text.getPaddingRight());
                int newPaddingLeft = paddingLeft + startOverhang;
                int newPaddingRight = paddingRight + endOverhang + safety;
                int desired = (int)Math.ceil(Math.max(advance, styled)) + newPaddingLeft + newPaddingRight + compoundLeft + compoundRight + safety;
                lastAvailable = capacity; lastRequired = desired;
                applying = true;
                reconciliations++;
                if (value.indexOf('\n') >= 0 || desired > capacity || value.codePointCount(0, value.length()) > 512) {
                    // Genuinely long text keeps the native font, allocation and marquee.
                    restoreGeometry(); appliedKey = geometry; return;
                }
                int width = Math.min((int)capacity, Math.max(desired, Math.min(originalMin, (int)capacity)));
                protectedIconLeft = iconX; protectedRootWidth = root.getWidth();
                boolean changed = false;
                if (left != null && left != root && left != text && isDescendant(text, left)) {
                    int leftX = localX(left, root);
                    int wanted = Math.min(boundary - leftX, textX - leftX + width + left.getPaddingRight());
                    if (wanted > left.getWidth() && wanted > 0) changed |= setWidth(left, wanted);
                }
                changed |= setWidth(text, width);
                if (text.getPaddingLeft() != newPaddingLeft || text.getPaddingRight() != newPaddingRight) {
                    text.setPadding(newPaddingLeft, paddingTop, newPaddingRight, paddingBottom); changed = true;
                }
                if (text.getMaxWidth() < width) { text.setMaxWidth(width); changed = true; }
                if (text.getMinWidth() > width) { text.setMinWidth(width); changed = true; }
                if (text.getEllipsize() != null) { text.setEllipsize(null); changed = true; }
                appliedKey = geometry;
                verifyIcon = changed && right != null;
                if (verifyIcon) refreshAfterApply();
            } catch (Throwable ignored) {
                // An unfamiliar native hierarchy must not destabilize SystemUI.
                restoreGeometry();
            } finally { applying = false; }
        }

        private void refreshAfterApply() {
            if (!queued && !closed) { queued = true; text.postOnAnimation(reconcile); }
        }
        private long externalKey(String value) {
            long key = value.hashCode();
            key = key * 31 + System.identityHashCode(text.getTypeface());
            key = key * 31 + Float.floatToIntBits(text.getTextSize());
            key = key * 31 + Float.floatToIntBits(text.getLetterSpacing());
            key = key * 31 + root.getWidth(); key = key * 31 + root.getHeight();
            key = key * 31 + (right == null ? 0 : right.getWidth());
            return key;
        }
        private void restoreGeometry() {
            try {
                setWidth(text, originalWidth);
                if (left != null && left != root && left != text) setWidth(left, originalLeftWidth);
                if (text.getPaddingLeft() != paddingLeft || text.getPaddingRight() != paddingRight)
                    text.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
                if (originalMinEms >= 0) text.setMinEms(originalMinEms); else if (originalMin >= 0) text.setMinWidth(originalMin);
                if (originalMaxEms >= 0) text.setMaxEms(originalMaxEms); else if (originalMax >= 0) text.setMaxWidth(originalMax);
                if (text.getEllipsize() != ellipsize) text.setEllipsize(ellipsize);
                text.setSelected(originalSelected);
            } catch (Throwable ignored) { }
        }
        @Override public void close() {
            if (closed) return;
            closed = true; text.removeCallbacks(reconcile); queued = false;
            text.removeTextChangedListener(watcher); text.removeOnAttachStateChangeListener(attachListener);
            root.removeOnLayoutChangeListener(layoutListener); text.removeOnLayoutChangeListener(layoutListener);
            if (left != null) left.removeOnLayoutChangeListener(layoutListener);
            if (right != null) right.removeOnLayoutChangeListener(layoutListener);
            applying = true; restoreGeometry();
            try {
                text.setTypeface(originalTypeface);
                text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, originalSize);
            } catch (Throwable ignored) { }
            applying = false;
            WeakReference<Binding> existing = BINDINGS.get(text);
            if (existing != null && existing.get() == this) BINDINGS.remove(text);
        }
    }
    private static int widthOf(View view) { return view == null || view.getLayoutParams() == null ? ViewGroup.LayoutParams.WRAP_CONTENT : view.getLayoutParams().width; }
    private static boolean setWidth(View view, int width) {
        ViewGroup.LayoutParams p = view.getLayoutParams();
        if (p == null || p.width == width) return false;
        p.width = width; view.setLayoutParams(p); return true;
    }
    private static boolean isDescendant(View child, View ancestor) {
        for (android.view.ViewParent p = child.getParent(); p instanceof View; p = p.getParent()) if (p == ancestor) return true;
        return false;
    }
    private static int localX(View child, View root) {
        if (child == root) return 0;
        int result = child.getLeft(); android.view.ViewParent parent = child.getParent();
        for (int count = 0; parent instanceof View && parent != root && count < 24; count++) {
            View v = (View)parent; result += v.getLeft() - v.getScrollX(); parent = v.getParent();
        }
        return parent == root ? result - root.getScrollX() : -1;
    }
}
