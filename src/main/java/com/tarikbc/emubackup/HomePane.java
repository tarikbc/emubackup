package com.tarikbc.emubackup;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The answer. One headline, one action, four facts. DESIGN.md §5 "Home reflow".
 *
 * <p>Wide: status on the left, where the eye lands first; facts on the right. Tall: one
 * column, facts last. Every state has exactly one thing to press, and it is the fix, not a
 * menu that contains the fix.
 */
final class HomePane extends Pane {

    private TextView stateWord, headline, detail, action, help;
    private TextView lastBackup, where, next, games;
    private HomeModel model;

    HomePane(ShellActivity host) {
        super(host);
    }

    @Override protected View create() {
        ShellActivity c = host;
        boolean tall = c.isTall();

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        // The help button's ring extends into the gutter so its text lines up with the
        // headline; the column must not clip it.
        left.setClipChildren(false);
        left.setClipToPadding(false);
        stateWord = Ui.bold(c, "", 14, R.color.text_secondary);
        stateWord.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(stateWord);
        headline = Ui.bold(c, "Checking your saves\u2026", tall ? 26 : 30, R.color.text_primary);
        left.addView(headline, top(c, 8));
        detail = Ui.text(c, "", 16, R.color.text_secondary);
        left.addView(detail, top(c, 10));
        action = Ui.primaryButton(c, "");
        action.setVisibility(View.INVISIBLE);
        // What the framework focuses on the first D-pad press, even if the window had no
        // focus while the model loaded (a posted requestFocus fails in touch mode).
        action.setFocusedByDefault(true);
        action.setOnClickListener(v -> {
            if (model != null) host.perform(model.report.action);
        });
        LinearLayout.LayoutParams alp = top(c, 24);
        alp.width = tall ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        left.addView(action, alp);
        help = Ui.text(c, "What does this mean?", 14, R.color.text_secondary);
        help.setBackgroundResource(R.drawable.rail_row);
        help.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        help.setFocusable(true);
        help.setClickable(true);
        help.setOnClickListener(v -> help());
        LinearLayout.LayoutParams hlp = top(c, 8);
        hlp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        hlp.leftMargin = -Ui.dp(c, 12);
        left.addView(help, hlp);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(c, R.color.surface));
        int p = Ui.dp(c, 20);
        card.setPadding(p, p, p, p);
        lastBackup = fact(card, "Last backup", R.drawable.ic_clock);
        where = fact(card, "Kept in", R.drawable.ic_cloud);
        next = fact(card, "Next backup", R.drawable.ic_calendar);
        games = fact(card, "Games found", R.drawable.ic_gamepad_2);

        if (tall) {
            ScrollView sv = new ScrollView(c);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dp(c, 20), Ui.dp(c, 24), Ui.dp(c, 20), Ui.dp(c, 24));
            col.setClipChildren(false);
            col.setClipToPadding(false);
            sv.setClipChildren(false);
            col.addView(left, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            col.addView(card, top(c, 24));
            sv.addView(col);
            return sv;
        }

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(Ui.dp(c, 32), Ui.dp(c, 24), Ui.dp(c, 32), Ui.dp(c, 24));
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f));
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f);
        rlp.leftMargin = Ui.dp(c, 28);
        root.addView(right, rlp);
        return root;
    }

    private TextView fact(LinearLayout card, String label, int icon) {
        LinearLayout.LayoutParams lp = top(host, card.getChildCount() == 0 ? 0 : 14);
        TextView cap = Ui.caption(host, label);
        cap.setGravity(Gravity.CENTER_VERTICAL);
        Ui.iconStart(cap, icon, R.color.text_tertiary, 14, 6);
        card.addView(cap, lp);
        TextView v = Ui.text(host, "\u2026", 17, R.color.text_primary);
        card.addView(v, top(host, 2));
        return v;
    }

    private static LinearLayout.LayoutParams top(android.content.Context c, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, dp);
        return lp;
    }

    @Override void refresh() {
        if (host.model() != null) render(host.model());
    }

    @Override void onModel(HomeModel m) {
        render(m);
    }

    void render(HomeModel m) {
        this.model = m;
        view();
        Safety.Report r = m.report;
        int hue = ShellActivity.hueOf(r.state);
        stateWord.setText(ShellActivity.wordOf(r.state));
        stateWord.setTextColor(Ui.color(host, hue));
        int glyph = r.state == Safety.State.SAFE ? R.drawable.ic_circle_check
                : r.state == Safety.State.ATTENTION ? R.drawable.ic_triangle_alert
                : r.state == Safety.State.PROBLEM ? R.drawable.ic_circle_x : R.drawable.ic_info;
        Ui.iconStart(stateWord, glyph, hue, 16, 6);
        int whereIcon = m.input.where == Safety.Where.DRIVE ? R.drawable.ic_cloud
                : m.input.where == Safety.Where.FOLDER ? R.drawable.ic_folder : R.drawable.ic_smartphone;
        Ui.iconStart((TextView) ((android.view.ViewGroup) where.getParent()).getChildAt(
                ((android.view.ViewGroup) where.getParent()).indexOfChild(where) - 1),
                whereIcon, R.color.text_tertiary, 14, 6);
        headline.setText(r.headline);
        detail.setText(r.detail);
        action.setText(r.actionLabel);
        action.setVisibility(View.VISIBLE);

        long now = m.input.nowMs;
        lastBackup.setText(m.input.lastBackupMs == 0 ? "Never"
                : capitalise(Ago.format(m.input.lastBackupMs, now)));
        where.setText(capitalise(m.whereName())
                + (m.input.destinationUnavailable ? " (for now)" : ""));
        next.setText(m.input.scheduled ? capitalise(m.input.scheduleDescription)
                : "Only when you press the button");
        StringBuilder g = new StringBuilder();
        if (m.scan == null || !m.scan.ok()) {
            g.append("Could not look");
        } else {
            g.append(m.input.gamesTotal);
            if (m.input.gamesStale > 0) g.append(", ").append(m.input.gamesStale).append(" changed");
            if (m.input.gamesLocked > 0) {
                g.append("; ").append(m.input.gamesLocked)
                        .append(m.input.gamesLocked == 1 ? " folder" : " folders").append(" locked");
            }
        }
        games.setText(g);
    }

    private static String capitalise(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override String[] legend() {
        return new String[] { "A", "Select", "B", "Back", "Y", "What is this?" };
    }

    @Override View defaultFocus() {
        view();
        return action != null && action.getVisibility() == View.VISIBLE ? action : null;
    }

    /** One entry per folder the backup could not read in full: the fix, or the honest why-not. */
    void showRemedies() {
        if (model == null) return;
        ShellActivity c = host;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(c, "Extra access runs as Android's shell user, which reads what most "
                + "emulators write but not files an app keeps to itself.", 14, R.color.text_secondary));
        java.util.List<java.util.List<TextView>> rows = new java.util.ArrayList<>();
        for (HomeModel.Problem pr : model.problems) {
            Remedies.Remedy r = Remedies.forTarget(pr.targetId, pr.emulator);
            LinearLayout entry = new LinearLayout(c);
            entry.setOrientation(LinearLayout.VERTICAL);
            entry.setBackground(Ui.card(c, R.color.surface_high));
            int p = Ui.dp(c, 14);
            entry.setPadding(p, p, p, p);
            entry.addView(Ui.bold(c, pr.label + " \u00b7 " + pr.emulator + " \u00b7 " + pr.files
                    + (pr.files == 1 ? " file" : " files") + (pr.setAside ? " \u00b7 set aside" : ""),
                    15, R.color.text_primary));
            entry.addView(Ui.text(c, r.text, 14, R.color.text_secondary), top(c, 6));
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            java.util.List<TextView> buttons = new java.util.ArrayList<>();
            if (r.fixable()) {
                TextView open = Ui.primaryButton(c, r.openLabel);
                open.setMinWidth(0);
                open.setMinHeight(Ui.dp(c, 44));
                open.setOnClickListener(v -> host.openApp(pr.pkg));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.rightMargin = Ui.dp(c, 10);
                row.addView(open, blp);
                buttons.add(open);
            }
            TextView aside = Ui.secondaryButton(c, pr.setAside ? "Include again" : "Set aside");
            aside.setMinWidth(0);
            aside.setMinHeight(Ui.dp(c, 44));
            aside.setOnClickListener(v -> {
                Prefs.setAside(host, pr.targetId, !pr.setAside);
                dismissSheet();
                host.reload();
            });
            row.addView(aside);
            buttons.add(aside);
            rows.add(buttons);
            entry.addView(row, top(c, 12));
            col.addView(entry, top(c, 12));
        }
        SheetView sheet = showCustomSheet("What to do", col, null, "Close", null);
        // An explicit order for the ring: up and down between rows, left and right within
        // one, Close at the end. Nothing else in the sheet can take it.
        TextView close = sheet.primaryButton();
        for (TextView b : flatten(rows)) b.setId(View.generateViewId());
        for (int i = 0; i < rows.size(); i++) {
            java.util.List<TextView> row = rows.get(i);
            View above = i == 0 ? null : rows.get(i - 1).get(0);
            View below = i + 1 < rows.size() ? rows.get(i + 1).get(0) : close;
            for (int j = 0; j < row.size(); j++) {
                TextView b = row.get(j);
                b.setNextFocusUpId(above == null ? b.getId() : above.getId());
                b.setNextFocusDownId(below.getId());
                b.setNextFocusLeftId(row.get(Math.max(0, j - 1)).getId());
                b.setNextFocusRightId(row.get(Math.min(row.size() - 1, j + 1)).getId());
            }
        }
        if (!rows.isEmpty()) close.setNextFocusUpId(rows.get(rows.size() - 1).get(0).getId());
    }

    private static java.util.List<TextView> flatten(java.util.List<java.util.List<TextView>> rows) {
        java.util.List<TextView> all = new java.util.ArrayList<>();
        for (java.util.List<TextView> r : rows) all.addAll(r);
        return all;
    }

    @Override void help() {
        if (model == null) return;
        String body;
        switch (model.report.state) {
            case SAFE:
                body = "EmuBackup found your saves and the newest copy of each is stored in "
                        + model.whereName() + ". Nothing has changed since then, or it changed "
                        + "very recently. Backing up again is always safe: only what changed "
                        + "is sent.";
                break;
            case ATTENTION:
                body = "Your saves are backed up, but one thing needs you. The button does "
                        + "that one thing. Nothing has been lost.";
                break;
            case PROBLEM:
                body = "Backups are not happening the way they were set up to. Until this is "
                        + "fixed, new progress exists only on this device. The button starts "
                        + "the fix.";
                break;
            default:
                body = "EmuBackup keeps a copy of your emulator saves somewhere safe, so a "
                        + "broken device or a bad update does not take your progress with it. "
                        + "The walkthrough takes about a minute.";
        }
        showSheet(model.report.headline, body, null, "OK", null);
    }
}
