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
        return show(host, title, body, secondary, primary, onPrimary, null);
    }

    /** @param onSecondary runs after the sheet closes on the secondary button; null = just close. */
    public static SheetView show(ViewGroup host, String title, String body,
                                 String secondary, String primary, Runnable onPrimary,
                                 Runnable onSecondary) {
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
            cancel.setOnClickListener(v -> {
                sheet.dismiss();
                if (onSecondary != null) onSecondary.run();
            });
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
        f.post(f::requestFocusFromTouch);
        return sheet;
    }

    /** A sheet with one line of text to fill in. The field takes focus, since typing is the point. */
    public static SheetView showInput(ViewGroup host, String title, String body, String initial,
                                      String secondary, String primary,
                                      java.util.function.Consumer<String> onPrimary) {
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
        if (body != null) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Ui.dp(c, 8);
            card.addView(Ui.text(c, body, 15, R.color.text_secondary), blp);
        }

        android.widget.EditText field = new android.widget.EditText(c);
        field.setSingleLine(true);
        field.setText(initial == null ? "" : initial);
        field.setSelection(field.getText().length());
        field.setTextColor(Ui.color(c, R.color.text_primary));
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        field.setBackground(Ui.card(c, R.color.surface_high));
        field.setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 12));
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Ui.dp(c, 16);
        card.addView(field, flp);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        TextView cancel = Ui.secondaryButton(c, secondary);
        cancel.setOnClickListener(v -> sheet.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = Ui.dp(c, 12);
        row.addView(cancel, clp);
        TextView go = Ui.primaryButton(c, primary);
        Runnable commit = () -> {
            sheet.dismiss();
            onPrimary.accept(field.getText().toString().trim());
        };
        go.setOnClickListener(v -> commit.run());
        field.setOnEditorActionListener((v, id, ev) -> {
            commit.run();
            return true;
        });
        row.addView(go);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(c, 20);
        card.addView(row, rlp);

        field.setId(View.generateViewId());
        cancel.setId(View.generateViewId());
        go.setId(View.generateViewId());
        field.setNextFocusDownId(cancel.getId());
        field.setNextFocusUpId(field.getId());
        field.setNextFocusLeftId(field.getId());
        field.setNextFocusRightId(field.getId());
        cancel.setNextFocusUpId(field.getId());
        cancel.setNextFocusDownId(cancel.getId());
        cancel.setNextFocusLeftId(cancel.getId());
        cancel.setNextFocusRightId(go.getId());
        go.setNextFocusUpId(field.getId());
        go.setNextFocusDownId(go.getId());
        go.setNextFocusLeftId(cancel.getId());
        go.setNextFocusRightId(go.getId());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(Ui.dp(c, 520), host.getWidth() - Ui.dp(c, 48)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        sheet.addView(card, lp);
        host.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        field.post(field::requestFocusFromTouch);
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
