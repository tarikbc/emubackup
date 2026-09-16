package com.tarikbc.emubackup;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A titled list of places to go. Used for destinations whose real pane has not landed yet,
 * so every screen stays reachable while the new ones are built one at a time.
 */
class LinksPane extends Pane {

    static final class Link {
        final String label, hint;
        final Runnable open;

        Link(String label, String hint, Runnable open) {
            this.label = label;
            this.hint = hint;
            this.open = open;
        }
    }

    private final String title, intro;
    private final Object[] rows; // String for a section header, Link for a row
    private View first;

    LinksPane(ShellActivity host, String title, String intro, Object... rows) {
        super(host);
        this.title = title;
        this.intro = intro;
        this.rows = rows;
    }

    @Override protected View create() {
        ShellActivity c = host;
        ScrollView sv = new ScrollView(c);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(c, 32), Ui.dp(c, 20), Ui.dp(c, 32), Ui.dp(c, 20));
        sv.addView(root);

        root.addView(Ui.bold(c, title, 24, R.color.text_primary));
        if (intro != null) root.addView(Ui.text(c, intro, 15, R.color.text_secondary), margin(c, 6, 0));

        for (Object o : rows) {
            if (o instanceof String) {
                root.addView(Ui.caption(c, (String) o), margin(c, 22, 0));
                continue;
            }
            Link l = (Link) o;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.focus_ring);
            row.setPadding(Ui.dp(c, 18), Ui.dp(c, 12), Ui.dp(c, 18), Ui.dp(c, 12));
            row.setMinimumHeight(Ui.dp(c, 60));
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnClickListener(v -> l.open.run());
            row.addView(Ui.text(c, l.label, 17, R.color.text_primary));
            if (l.hint != null) row.addView(Ui.text(c, l.hint, 14, R.color.text_secondary));
            root.addView(row, margin(c, 10, 0));
            if (first == null) first = row;
        }
        return sv;
    }

    private static LinearLayout.LayoutParams margin(android.content.Context c, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, top);
        lp.bottomMargin = Ui.dp(c, bottom);
        return lp;
    }

    @Override String[] legend() {
        return new String[] { "A", "Open", "B", "Back" };
    }

    @Override View defaultFocus() {
        view();
        return first;
    }
}
