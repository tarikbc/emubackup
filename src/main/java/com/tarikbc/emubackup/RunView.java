package com.tarikbc.emubackup;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The one progress screen. DESIGN.md §6 "Progress".
 *
 * <p>A plain-words phase, the item being worked on, a bar with its count, and one button:
 * Stop while running, Close when done. On finish: a one-line outcome in its hue and every
 * problem named in full. Backups, restores, checks and exports all look like this, so a
 * person learns it once.
 */
public final class RunView extends FrameLayout {

    private final TextView phase, item, detail, count, outcome, body;
    private final ProgressBar bar;
    private final TextView button;
    private Runnable onButton;

    public RunView(Context c, String title, String intro, boolean tall) {
        super(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        int gx = Ui.dp(c, tall ? 24 : 48), gy = Ui.dp(c, 24);
        col.setPadding(gx, gy, gx, gy);

        phase = Ui.bold(c, title, tall ? 26 : 30, R.color.text_primary);
        col.addView(phase);
        item = Ui.text(c, "", 17, R.color.text_primary);
        col.addView(item, top(c, 12));
        detail = Ui.text(c, intro == null ? "" : intro, 14, R.color.text_secondary);
        col.addView(detail, top(c, 4));

        bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.color(c, R.color.accent)));
        bar.setIndeterminateTintList(ColorStateList.valueOf(Ui.color(c, R.color.accent)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.color(c, R.color.surface_high)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 8));
        blp.topMargin = Ui.dp(c, 20);
        col.addView(bar, blp);
        count = Ui.text(c, "", 14, R.color.text_secondary);
        col.addView(count, top(c, 6));

        outcome = Ui.bold(c, "", 22, R.color.ok);
        outcome.setVisibility(GONE);
        col.addView(outcome, top(c, 20));
        body = Ui.text(c, "", 15, R.color.text_secondary);
        body.setVisibility(GONE);
        col.addView(body, top(c, 8));

        button = Ui.secondaryButton(c, "Stop");
        button.setFocusedByDefault(true);
        button.setOnClickListener(v -> {
            if (onButton != null) onButton.run();
        });
        LinearLayout.LayoutParams lp = top(c, 28);
        lp.width = tall ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        col.addView(button, lp);

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        addView(sv, new LayoutParams(tall ? ViewGroup.LayoutParams.MATCH_PARENT : Ui.dp(c, 640),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, dp);
        return lp;
    }

    public TextView button() {
        return button;
    }

    /** What the button does now. */
    public void onButton(String label, Runnable r) {
        button.setText(label);
        button.setEnabled(true);
        onButton = r;
    }

    /** The button as a primary (accent) or secondary action. */
    public void primary(boolean primary) {
        button.setBackgroundResource(primary ? R.drawable.button_primary : R.drawable.focus_ring);
        button.setTextColor(Ui.color(getContext(), primary ? R.color.ink_black : R.color.text_primary));
    }

    public void waiting(String label) {
        button.setText(label);
        button.setEnabled(false);
    }

    public void phase(String text) {
        phase.setText(text);
    }

    public void item(String text) {
        item.setText(text == null ? "" : text);
    }

    public void detail(String text) {
        detail.setText(text == null ? "" : text);
    }

    /** @param done -1 for unknown progress. */
    public void progress(int done, int total, String countText) {
        if (done < 0 || total <= 0) {
            bar.setIndeterminate(true);
        } else {
            bar.setIndeterminate(false);
            bar.setMax(total);
            bar.setProgress(done);
        }
        count.setText(countText == null ? "" : countText);
    }

    /** The end. The bar goes; the outcome takes its hue; the button becomes Close. */
    public void finish(boolean ok, boolean stopped, String headline, String text, Runnable onClose) {
        bar.setVisibility(GONE);
        count.setVisibility(GONE);
        item.setVisibility(GONE);
        detail.setVisibility(GONE);
        // The headline takes the title's place and its hue; two lines for one fact is noise.
        phase.setText(headline);
        phase.setTextColor(Ui.color(getContext(), ok ? R.color.ok : stopped ? R.color.text_secondary : R.color.danger));
        outcome.setVisibility(GONE);
        if (text != null && !text.isEmpty()) {
            body.setText(text);
            body.setVisibility(VISIBLE);
        }
        primary(true);
        onButton("Close", onClose);
        // From a key, so it takes in touch mode too; otherwise the first A press only focuses
        // Close and a second one is needed to leave (docs/INPUT.md, touch mode).
        button.post(() -> Ui.focus(button, true));
    }
}
