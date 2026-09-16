package com.tarikbc.emubackup;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * The few widgets every v2 screen is built from, so they look and focus the same everywhere.
 *
 * <p>Buttons are {@link TextView}s with the app's own selectors rather than framework Buttons,
 * because the framework Material button shows focus as a faint ripple that is invisible from a
 * couch. Every button here is focusable and clickable, so it works the same by touch and pad.
 */
final class Ui {

    private Ui() {}

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int color(Context c, int res) {
        return c.getResources().getColor(res, null);
    }

    static TextView text(Context c, CharSequence s, int sp, int colorRes) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color(c, colorRes));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    static TextView bold(Context c, CharSequence s, int sp, int colorRes) {
        TextView t = text(c, s, sp, colorRes);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** A small uppercase label above a value. */
    static TextView caption(Context c, CharSequence s) {
        TextView t = text(c, s.toString().toUpperCase(java.util.Locale.getDefault()), 12, R.color.text_tertiary);
        t.setLetterSpacing(0.08f);
        return t;
    }

    static TextView primaryButton(Context c, CharSequence label) {
        TextView t = bold(c, label, 17, R.color.ink_black);
        t.setBackgroundResource(R.drawable.button_primary);
        return button(c, t);
    }

    static TextView secondaryButton(Context c, CharSequence label) {
        TextView t = bold(c, label, 17, R.color.text_primary);
        t.setBackgroundResource(R.drawable.focus_ring);
        return button(c, t);
    }

    private static TextView button(Context c, TextView t) {
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 24), 0, dp(c, 24), 0);
        t.setMinHeight(dp(c, 56));
        t.setMinWidth(dp(c, 200));
        t.setFocusable(true);
        t.setClickable(true);
        return t;
    }

    /**
     * Focuses a RecyclerView row, now if it exists, otherwise as soon as it is laid out.
     *
     * <p>A fresh adapter, or a pane that just came on screen, has no rows until the next layout
     * pass. Pre-draw is the first moment after layout, but a single pre-draw can still precede
     * the rows, and no further draw is guaranteed, so the listener stays on for a few frames
     * and asks for another frame each time.
     */
    static void focusRow(androidx.recyclerview.widget.RecyclerView list, int pos, boolean fromKey) {
        if (list == null || pos < 0) return;
        androidx.recyclerview.widget.RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
        if (h != null) {
            focus(h.itemView, fromKey);
            return;
        }
        list.scrollToPosition(pos);
        list.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    int tries = 8;

                    @Override public boolean onPreDraw() {
                        androidx.recyclerview.widget.RecyclerView.ViewHolder h2 =
                                list.findViewHolderForAdapterPosition(pos);
                        if (h2 != null || --tries <= 0) {
                            list.getViewTreeObserver().removeOnPreDrawListener(this);
                            if (h2 != null) focus(h2.itemView, fromKey);
                        } else {
                            list.postInvalidate();
                        }
                        return true;
                    }
                });
    }

    /**
     * Gives focus to a view, leaving touch mode when a gamepad button asked for it.
     *
     * <p>Only navigation keys (the D-pad, and A once remapped to DPAD_CENTER) make the framework
     * leave touch mode. L1/R1, B, X and Y are ordinary buttons, so after any touch, or in a fresh
     * window, a plain {@code requestFocus} on a row returns false and nothing shows a cursor.
     * {@code requestFocusFromTouch} is the one public call that leaves touch mode first. Touch
     * itself must not call it, or every tap would grow a ring.
     */
    static boolean focus(View v, boolean fromKey) {
        if (v == null) return false;
        return fromKey ? v.requestFocusFromTouch() : v.requestFocus();
    }

    static View dot(Context c, int colorRes, int sizeDp) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color(c, colorRes));
        v.setBackground(d);
        v.setLayoutParams(new android.view.ViewGroup.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    static GradientDrawable card(Context c, int colorRes) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color(c, colorRes));
        d.setCornerRadius(dp(c, 14));
        return d;
    }
}
