package com.tarikbc.emubackup;

import android.view.View;
import android.view.ViewGroup;

/**
 * One rail destination's content, hosted by {@link ShellActivity}.
 *
 * <p>A pane is a view plus the three things the shell needs to know about it: what the
 * buttons mean here, where focus should land, and what B does before it reaches the rail.
 */
abstract class Pane {

    protected final ShellActivity host;
    private View view;
    private SheetView sheet;

    Pane(ShellActivity host) {
        this.host = host;
    }

    final View view() {
        if (view == null) view = create();
        return view;
    }

    protected abstract View create();

    /** Drops the built view so the next {@link #view()} builds it for the current form. */
    void invalidateView() {
        dismissSheet();
        view = null;
    }

    /** The shell has new data, or the pane just came on screen. */
    void refresh() { }

    /** A fresh model has been loaded. Called for every pane that exists, shown or not. */
    void onModel(HomeModel m) { }

    /** X, and whatever visible button means the same thing. */
    void onX() { }

    /** L2 / R2: the pane's own previous / next, where it has one (console filter on Games). */
    void onL2() { }
    void onR2() { }

    /** Pairs for the legend bar, without the shell's own L1/R1 entry. */
    String[] legend() {
        return new String[] { "A", "Select", "B", "Back" };
    }

    /** Where focus lands when the pane is shown by gamepad. Null: the shell picks. */
    View defaultFocus() {
        return null;
    }

    /** Y, and the touch button that means the same thing. */
    void help() { }

    /** @return true when B was used up inside the pane (a sheet closed, a detail left). */
    boolean back() {
        if (sheet != null && sheet.isShowing()) {
            sheet.dismiss();
            sheet = null;
            return true;
        }
        return false;
    }

    protected final void showSheet(String title, String body, String secondary, String primary,
                                   Runnable onPrimary) {
        ViewGroup over = host.paneHost();
        if (sheet != null) sheet.dismiss();
        sheet = SheetView.show(over, title, body, secondary, primary, onPrimary);
    }

    protected final void showSheet(String title, String body, String secondary, String primary,
                                   Runnable onPrimary, Runnable onSecondary) {
        ViewGroup over = host.paneHost();
        if (sheet != null) sheet.dismiss();
        sheet = SheetView.show(over, title, body, secondary, primary, onPrimary, onSecondary);
    }

    protected final void showInputSheet(String title, String body, String initial,
                                        String secondary, String primary,
                                        java.util.function.Consumer<String> onPrimary) {
        ViewGroup over = host.paneHost();
        if (sheet != null) sheet.dismiss();
        sheet = SheetView.showInput(over, title, body, initial, secondary, primary, onPrimary);
    }

    protected final void dismissSheet() {
        if (sheet != null) sheet.dismiss();
        sheet = null;
    }
}
