package com.tarikbc.emubackup;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * An in-pane confirmation or explanation.
 *
 * <p>Not an {@code AlertDialog}: a dialog is a separate window and the Activity's
 * {@code dispatchKeyEvent} never sees a gamepad button while one is up, so B would not close
 * it and A would not press it. This is an ordinary view laid over the pane, and focus is fenced
 * to its buttons so the D-pad cannot wander underneath.
 *
 * <p>The secondary (cancel) button takes focus first when there is one. Someone holding A to
 * move through a list will land here still pressing; the safe answer must be the one under
 * their thumb.
 */
public final class SheetView extends FrameLayout {

    private final ViewGroup host;

    private SheetView(ViewGroup host) {
        super(host.getContext());
        this.host = host;
    }

    public static SheetView show(ViewGroup host, String title, String body,
                                 String secondary, String primary, Runnable onPrimary) {
        Context c = host.getContext();
        SheetView sheet = new SheetView(host);
        sheet.setBackgroundColor(0xB3000000);
        sheet.setClickable(true);
        sheet.setOnClickListener(v -> sheet.dismiss());

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(c, R.color.surface));
        int p = Ui.dp(c, 24);
        card.setPadding(p, p, p, p);
        card.setClickable(true);
        card.addView(Ui.bold(c, title, 22, R.color.text_primary));
        TextView b = Ui.text(c, body, 16, R.color.text_secondary);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(c, 10);
        card.addView(b, blp);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        TextView cancel = null;
        if (secondary != null) {
            cancel = Ui.secondaryButton(c, secondary);
            cancel.setOnClickListener(v -> sheet.dismiss());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(c, 12);
            row.addView(cancel, clp);
        }
        TextView go = Ui.primaryButton(c, primary);
        go.setOnClickListener(v -> {
            sheet.dismiss();
            if (onPrimary != null) onPrimary.run();
        });
        row.addView(go);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(c, 24);
        card.addView(row, rlp);

        // Fence focus: every direction from either button lands on one of the two.
        go.setId(View.generateViewId());
        View first = go;
        if (cancel != null) {
            cancel.setId(View.generateViewId());
            fence(cancel, cancel.getId(), go.getId());
            fence(go, cancel.getId(), go.getId());
            first = cancel;
        } else {
            fence(go, go.getId(), go.getId());
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(Ui.dp(c, 520), host.getWidth() - Ui.dp(c, 48)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        sheet.addView(card, lp);
        host.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final View f = first;
        f.post(f::requestFocus);
        return sheet;
    }

    private static void fence(View v, int left, int right) {
        v.setNextFocusLeftId(left);
        v.setNextFocusRightId(right);
        v.setNextFocusUpId(v.getId());
        v.setNextFocusDownId(v.getId());
    }

    public boolean isShowing() {
        return getParent() != null;
    }

    public void dismiss() {
        if (getParent() == host) host.removeView(this);
    }
}
