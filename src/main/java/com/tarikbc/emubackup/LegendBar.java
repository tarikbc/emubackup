package com.tarikbc.emubackup;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The strip along the bottom that says what the buttons do right now.
 *
 * <p>Console UIs have this and it is the single biggest affordance for someone who has never
 * used the app: they never have to guess whether A selects or B goes back, because it says so,
 * on every screen, in the same place. Set from each screen with {@code setLegend("A", "Select",
 * "B", "Back")} and it redraws.
 */
public final class LegendBar extends LinearLayout {

    public LegendBar(Context ctx) {
        super(ctx);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(getResources().getColor(R.color.surface_low, null));
        int p = dp(16);
        setPadding(p, 0, p, 0);
    }

    /** Pairs of button label and meaning: "A", "Select", "B", "Back", ... */
    public void set(String... pairs) {
        removeAllViews();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            addView(chip(pairs[i]));
            addView(label(pairs[i + 1]));
        }
    }

    private TextView chip(String button) {
        TextView t = new TextView(getContext());
        t.setText(button);
        t.setTextColor(getResources().getColor(R.color.ink_black, null));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(26));
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getResources().getColor(R.color.text_secondary, null));
        bg.setCornerRadius(dp(13));
        t.setBackground(bg);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(26));
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView label(String meaning) {
        TextView t = new TextView(getContext());
        t.setText(meaning);
        t.setTextColor(getResources().getColor(R.color.text_secondary, null));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(28);
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
