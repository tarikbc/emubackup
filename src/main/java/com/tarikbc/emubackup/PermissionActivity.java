package com.tarikbc.emubackup;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Two independent cards: the grant everything depends on, and the optional one.
 *
 * <p>Built programmatically because it is a short linear screen whose content depends on live
 * state; an XML layout plus a binder would be more moving parts than the screen has.
 *
 * <p>{@code DESIGN.md} section 8: a locked capability is shown with its reason and its unlock
 * path, never hidden, and the Shizuku card never blocks the rest of the app.
 */
public class PermissionActivity extends Activity {

    private LinearLayout root;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(color(R.color.ink_black));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(root);
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        // Rebuilt on every resume: the user may have just returned from the Settings screen
        // where they granted or revoked the very permission this screen is about.
        root.removeAllViews();
        root.addView(title(getString(R.string.permissions_title)));

        boolean allFiles = Permissions.hasAllFiles();
        LinearLayout a = addCard(allFiles ? R.color.ok : R.color.warn, dp(20));
        a.addView(cardTitle(allFiles
                ? getString(R.string.all_files_granted)
                : "Shared-storage saves — required"));
        a.addView(body(getString(R.string.all_files_why)));
        if (!allFiles) {
            Button b = new Button(this);
            b.setText(R.string.grant_all_files);
            b.setTextColor(color(R.color.ink_black));
            b.setBackgroundTintList(ColorStateList.valueOf(color(R.color.accent)));
            b.setOnClickListener(v -> Permissions.requestAllFiles(this));
            a.addView(b, marginTop(dp(14)));
        }

        LinearLayout s = addCard(R.color.text_tertiary, dp(12));
        s.addView(cardTitle(getString(R.string.shizuku_title)));
        s.addView(body(getString(R.string.shizuku_why)));
        s.addView(body(getString(R.string.shizuku_pending)));
    }

    /** Adds a card to the screen and returns the column to put its content in. */
    private LinearLayout addCard(int hueRes, int topMargin) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setBackgroundColor(color(R.color.surface));

        View hue = new View(this);
        hue.setBackgroundColor(color(hueRes));
        outer.addView(hue, new LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(16), dp(16), dp(16));
        outer.addView(inner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(outer, marginTop(topMargin));
        return inner;
    }

    private TextView title(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color(R.color.text_primary));
        v.setTextSize(22);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private TextView cardTitle(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color(R.color.text_primary));
        v.setTextSize(17);
        return v;
    }

    private TextView body(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color(R.color.text_secondary));
        v.setTextSize(14);
        v.setPadding(0, dp(8), 0, 0);
        v.setLineSpacing(dp(3), 1f);
        return v;
    }

    private LinearLayout.LayoutParams marginTop(int px) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = px;
        return p;
    }

    private int dp(int v) { return (int) (getResources().getDisplayMetrics().density * v); }

    private int color(int res) { return getResources().getColor(res, null); }
}
