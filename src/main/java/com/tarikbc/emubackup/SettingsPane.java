package com.tarikbc.emubackup;

import android.content.Intent;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Settings as plain sections, with Advanced at the end.
 *
 * <p>Wide: the sections on the left, the chosen section's controls on the right. Tall: the
 * sections, then a section's controls in their place. Choices are chips edited in place; the
 * three things that need another app's screen (a folder picker, Shizuku, Google's device code)
 * say their state here in plain words and hand off. Y explains the focused section.
 * DESIGN.md §6, §10, §11.
 */
final class SettingsPane extends Pane {

    enum Section {
        WHERE("Where backups go"), WHEN("When backups run"), WHAT("What to include"),
        KEEP("How much to keep"), ACCESS("Storage and extra access"), ACCOUNT("Google account"),
        NOTIFY("Notifications"), ADVANCED("Advanced");

        final String title;

        Section(String title) {
            this.title = title;
        }
    }

    private static final long GB = 1024L * 1024L * 1024L;

    private HomeModel model;
    private FrameLayout root;
    private View listView;
    private LinearLayout sections;
    private FrameLayout detailHost;
    private final TextView[] rows = new TextView[Section.values().length];
    private Section selected = Section.WHERE;
    private boolean detailOpen;
    private String refocusTag;

    SettingsPane(ShellActivity host) {
        super(host);
    }

    @Override protected View create() {
        root = new FrameLayout(host);
        if (host.isTall()) {
            listView = buildSections();
            root.addView(listView);
            detailHost = new FrameLayout(host);
            if (detailOpen) {
                root.removeView(listView);
                root.addView(detailHost);
                renderDetail();
            }
        } else {
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.HORIZONTAL);
            listView = buildSections();
            row.addView(listView, new LinearLayout.LayoutParams(Ui.dp(host, 280),
                    ViewGroup.LayoutParams.MATCH_PARENT));
            detailHost = new FrameLayout(host);
            row.addView(detailHost, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            root.addView(row);
            renderDetail();
        }
        return root;
    }

    private View buildSections() {
        ShellActivity c = host;
        boolean tall = c.isTall();
        ScrollView sv = new ScrollView(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int gx = Ui.dp(c, tall ? 20 : 32), gy = Ui.dp(c, tall ? 16 : 20);
        col.setPadding(gx, gy, tall ? gx : Ui.dp(c, 12), gy);
        sv.addView(col);
        col.addView(Ui.bold(c, "Settings", 24, R.color.text_primary));
        sections = new LinearLayout(c);
        sections.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(c, 12);
        col.addView(sections, slp);
        for (Section s : Section.values()) {
            TextView row = Ui.text(c, s.title, 17, R.color.text_primary);
            row.setBackgroundResource(R.drawable.rail_row);
            row.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(Ui.dp(c, 52));
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnClickListener(v -> select(s, true));
            row.setOnFocusChangeListener((v, has) -> {
                // In the wide form, moving along the list previews the section; A commits by
                // moving into it. Touch only ever clicks.
                if (has && !tall && s != selected) select(s, false);
            });
            rows[s.ordinal()] = row;
            if (s == Section.WHERE) row.setFocusedByDefault(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 2);
            sections.addView(row, lp);
        }
        highlight();
        return sv;
    }

    private void highlight() {
        for (Section s : Section.values()) {
            TextView r = rows[s.ordinal()];
            if (r == null) continue;
            boolean on = s == selected;
            r.setTextColor(Ui.color(host, on ? R.color.accent : R.color.text_primary));
            r.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    /** Shows a section; {@code enter} moves focus into it (tall: replaces the list). */
    private void select(Section s, boolean enter) {
        selected = s;
        highlight();
        if (host.isTall()) {
            if (!enter) return;
            detailOpen = true;
            root.removeView(listView);
            if (detailHost.getParent() == null) root.addView(detailHost);
            renderDetail();
            host.refreshLegend();
            focusFirstControl();
            return;
        }
        renderDetail();
        if (enter) focusFirstControl();
    }

    /** Moves the cursor into the section, but only for a cursor: a tap must not grow a ring. */
    private void focusFirstControl() {
        if (detailHost.isInTouchMode()) return;
        View first = firstFocusable(detailHost);
        if (first != null) first.post(() -> Ui.focus(first, true));
    }

    private static View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;
            if (v.isFocusable() && !(v instanceof ViewGroup && !v.isFocusableInTouchMode()
                    && ((ViewGroup) v).getDescendantFocusability() == ViewGroup.FOCUS_AFTER_DESCENDANTS
                    && firstFocusable((ViewGroup) v) != null)) {
                if (!(v instanceof ScrollView)) return v;
            }
            if (v instanceof ViewGroup) {
                View inner = firstFocusable((ViewGroup) v);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private void closeDetail() {
        detailOpen = false;
        root.removeView(detailHost);
        if (listView.getParent() == null) root.addView(listView);
        host.refreshLegend();
        Ui.focus(rows[selected.ordinal()], true);
    }

    @Override void refresh() {
        if (host.model() != null) onModel(host.model());
    }

    @Override void onModel(HomeModel m) {
        model = m;
        if (root != null && detailHost != null && (detailOpen || !host.isTall())) renderDetail();
    }

    // ---- details ----

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(host, dp);
        return lp;
    }

    private void renderDetail() {
        detailHost.removeAllViews();
        ShellActivity c = host;
        boolean tall = c.isTall();
        ScrollView sv = new ScrollView(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int gx = Ui.dp(c, tall ? 20 : 24), gy = Ui.dp(c, tall ? 16 : 20);
        col.setPadding(gx, gy, Ui.dp(c, tall ? 20 : 32), gy);
        sv.addView(col);
        detailHost.addView(sv);

        col.addView(Ui.bold(c, selected.title, 22, R.color.text_primary));
        if (model == null) {
            col.addView(Ui.text(c, "Checking\u2026", 15, R.color.text_secondary), top(8));
            return;
        }
        Settings s = Prefs.settings(c);
        switch (selected) {
            case WHERE: where(col); break;
            case WHEN: when(col, s); break;
            case WHAT: what(col, s); break;
            case KEEP: keep(col, s); break;
            case ACCESS: access(col); break;
            case ACCOUNT: account(col); break;
            case NOTIFY: notify(col); break;
            case ADVANCED: advanced(col); break;
        }
        if (refocusTag != null) {
            View again = col.findViewWithTag(refocusTag);
            refocusTag = null;
            if (again != null && !col.isInTouchMode()) again.post(() -> Ui.focus(again, true));
        }
    }

    private TextView para(LinearLayout col, String text) {
        TextView t = Ui.text(host, text, 15, R.color.text_secondary);
        col.addView(t, top(10));
        return t;
    }

    private TextView value(LinearLayout col, String text) {
        TextView t = Ui.text(host, text, 17, R.color.text_primary);
        col.addView(t, top(8));
        return t;
    }

    private TextView caption(LinearLayout col, String text) {
        TextView t = Ui.caption(host, text);
        col.addView(t, top(22));
        return t;
    }

    private TextView button(LinearLayout col, String label, boolean primary, Runnable go) {
        TextView b = primary ? Ui.primaryButton(host, label) : Ui.secondaryButton(host, label);
        b.setTag("btn:" + label);
        b.setOnClickListener(v -> go.run());
        LinearLayout.LayoutParams lp = top(16);
        lp.width = host.isTall() ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        col.addView(b, lp);
        return b;
    }

    /** A row of chips, one of which may be on. */
    private void chips(LinearLayout col, String group, Object... labelOnAction) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i + 2 < labelOnAction.length + 1; i += 3) {
            String label = (String) labelOnAction[i];
            boolean on = (Boolean) labelOnAction[i + 1];
            Runnable act = (Runnable) labelOnAction[i + 2];
            TextView t = Ui.bold(host, label, 15, on ? R.color.accent : R.color.text_secondary);
            t.setBackgroundResource(R.drawable.focus_ring);
            t.setPadding(Ui.dp(host, 18), Ui.dp(host, 10), Ui.dp(host, 18), Ui.dp(host, 10));
            t.setMinHeight(Ui.dp(host, 48));
            t.setGravity(Gravity.CENTER);
            t.setFocusable(true);
            t.setClickable(true);
            t.setTag(group + ":" + label);
            t.setOnClickListener(v -> {
                refocusTag = group + ":" + label;
                act.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(host, 8);
            row.addView(t, lp);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(host);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        col.addView(hs, top(10));
    }

    private void set(Settings s) {
        Prefs.save(host, s);
        renderDetail();
        host.reload();
    }

    private void where(LinearLayout col) {
        String name;
        switch (model.input.where) {
            case DRIVE: name = "Google Drive"; break;
            case FOLDER: name = model.whereName(); break;
            default: name = "This device"; break;
        }
        value(col, name);
        if (model.input.destinationUnavailable) {
            TextView w = para(col, "The place you chose is not available right now, so backups "
                    + "go to this device until it is back.");
            w.setTextColor(Ui.color(host, R.color.warn));
        }
        switch (model.input.where) {
            case DRIVE:
                para(col, "Backups survive losing or wiping the device. Only what changed is "
                        + "sent each time.");
                break;
            case FOLDER:
                para(col, "A folder you picked: an SD card, a USB drive, or a folder a sync app "
                        + "watches. It is as safe as that place is.");
                break;
            default:
                para(col, "A folder in this device's own storage. It protects against an "
                        + "emulator wiping its saves, and against nothing that happens to the "
                        + "device itself.");
        }
        para(col, "Changing this does not move or delete anything already backed up.");
        button(col, "Change where backups go", true, () -> host.open(DestinationActivity.class));
    }

    private void when(LinearLayout col, Settings s) {
        value(col, capitalise(s.describeSchedule()));
        caption(col, "How often");
        chips(col, "freq",
                "Off", s.frequency == Settings.Frequency.OFF, (Runnable) () -> set(s.withFrequency(Settings.Frequency.OFF)),
                "Daily", s.frequency == Settings.Frequency.DAILY, (Runnable) () -> set(s.withFrequency(Settings.Frequency.DAILY)),
                "Weekly", s.frequency == Settings.Frequency.WEEKLY, (Runnable) () -> set(s.withFrequency(Settings.Frequency.WEEKLY)));
        if (s.scheduled() && !BackupJobScheduler.isScheduled(host)) {
            TextView w = para(col, "The system has no job registered for this. Set it to Off and "
                    + "back on to register it again.");
            w.setTextColor(Ui.color(host, R.color.danger));
        }
        caption(col, "Only when");
        chips(col, "cond",
                "On charge", s.requiresCharging, (Runnable) () -> set(s.withCharging(!s.requiresCharging)),
                "On Wi-Fi", s.requiresUnmetered, (Runnable) () -> set(s.withUnmetered(!s.requiresUnmetered)));
        para(col, "A first backup is hundreds of megabytes; run that one by hand from Home. After "
                + "it, only what changed is sent, which takes seconds. With both conditions off, a "
                + "backup can run on mobile data, on battery.");
    }

    private void what(LinearLayout col, Settings s) {
        para(col, "Game saves are always included. These two are not, until you say so.");
        caption(col, "Also include");
        chips(col, "what",
                "Save states" + size(Category.STATE), s.includeStates, (Runnable) () -> set(s.withStates(!s.includeStates)),
                "Emulator keys" + size(Category.KEY), s.includeKeys, (Runnable) () -> set(s.withKeys(!s.includeKeys)));
        para(col, "Save states are large, tied to one emulator build, and recreated by playing. "
                + "Keys are not save data and are not always yours to copy.");
    }

    private String size(Category c) {
        long bytes = ScanSession.lastKnownBytes(c);
        return bytes <= 0 ? "" : " \u00b7 " + Sizes.human(bytes);
    }

    private void keep(LinearLayout col, Settings s) {
        value(col, s.keepVersions == RetentionPolicy.UNLIMITED ? "Every backup is kept"
                : s.keepVersions + " backups, then the oldest go");
        caption(col, "How many");
        chips(col, "keep",
                "5", s.keepVersions == 5, (Runnable) () -> set(s.withKeep(5)),
                "20", s.keepVersions == 20, (Runnable) () -> set(s.withKeep(20)),
                "50", s.keepVersions == 50, (Runnable) () -> set(s.withKeep(50)),
                "All", s.keepVersions == RetentionPolicy.UNLIMITED, (Runnable) () -> set(s.withKeep(RetentionPolicy.UNLIMITED)));
        caption(col, "Total size");
        chips(col, "cap",
                "2 GB", s.budgetBytes == 2L * GB, (Runnable) () -> set(s.withBudget(2L * GB)),
                "10 GB", s.budgetBytes == 10L * GB, (Runnable) () -> set(s.withBudget(10L * GB)),
                "50 GB", s.budgetBytes == 50L * GB, (Runnable) () -> set(s.withBudget(50L * GB)),
                "No limit", s.budgetBytes == RetentionPolicy.UNLIMITED, (Runnable) () -> set(s.withBudget(RetentionPolicy.UNLIMITED)));
        if (s.includeStates && s.budgetBytes == RetentionPolicy.UNLIMITED) {
            TextView w = para(col, "Save states are on and there is no size limit: "
                    + Sizes.human(ScanSession.lastKnownBytes(Category.STATE)) + " per full backup, kept "
                    + (s.keepVersions == RetentionPolicy.UNLIMITED ? "forever" : s.keepVersions + " deep")
                    + ", is a lot of storage to use without deciding to.");
            w.setTextColor(Ui.color(host, R.color.warn));
        }
        para(col, "A backup that a newer one still builds on is never removed, nor is a safety "
                + "copy, nor the newest backup. So a limit smaller than one backup does not empty "
                + "the store.");
    }

    private void access(LinearLayout col) {
        caption(col, "Storage access");
        if (model.input.storageAccess) {
            value(col, "Granted");
            para(col, "EmuBackup can read the save folders in shared storage.");
        } else {
            TextView v = value(col, "Not granted");
            v.setTextColor(Ui.color(host, R.color.danger));
            para(col, "Without it nothing can be read. Emulator saves live in folders that "
                    + "belong to other apps, and this is the only way to reach them.");
            button(col, "Grant storage access", true, () -> Permissions.requestAllFiles(host));
        }

        caption(col, "Extra access, with Shizuku");
        ShizukuGate.Status st = model.scan == null ? null : model.scan.shizuku;
        int locked = model.input.gamesLocked;
        String lockedLine = locked == 0 ? "" : " " + locked + (locked == 1 ? " save folder" : " save folders")
                + " on this device need it.";
        if (st == null) {
            value(col, "Unknown");
        } else switch (st.state) {
            case READY:
                value(col, "Ready");
                para(col, "Running as " + st.identity() + ", server v" + st.serverVersion + ". Folders only "
                        + "the emulator can see are backed up too. Shizuku stops at every reboot, "
                        + "and a scheduled backup then skips those folders and says so.");
                break;
            case NOT_INSTALLED:
                value(col, "Not set up");
                para(col, "Some emulators keep saves in folders only they can see: GameCube, Wii, "
                        + "PlayStation, 3DS. Shizuku is a free app that opens them, started once "
                        + "over a USB cable or wireless debugging." + lockedLine);
                button(col, "Set up extra access", true, () -> host.open(PermissionActivity.class));
                break;
            case NOT_RUNNING:
                value(col, "Shizuku is installed but not running");
                para(col, "It stops at every reboot. Start it from the Shizuku app." + lockedLine);
                button(col, "Open Shizuku", true, () -> host.open(PermissionActivity.class));
                break;
            case NEEDS_PERMISSION:
                value(col, "Shizuku is running, EmuBackup is not allowed yet");
                para(col, "One tap in the dialog Shizuku shows." + lockedLine);
                button(col, "Ask for access", true, ShizukuGate::requestPermission);
                break;
            default:
                TextView v = value(col, "Could not be used");
                v.setTextColor(Ui.color(host, R.color.danger));
                para(col, st.detail == null ? "Shizuku is granted but did not work on this device."
                        : st.detail);
                button(col, "See the details", false, () -> host.open(PermissionActivity.class));
        }
    }

    private void account(LinearLayout col) {
        boolean linked = new DriveTokens(host).linked();
        boolean configured = DriveClient.of(host).configured();
        if (linked) {
            value(col, "Linked");
            para(col, "Backups can go to Google Drive. Nothing else is read from your account.");
            button(col, "Manage the link", false, () -> host.open(DriveLinkActivity.class));
        } else {
            value(col, "Not linked");
            para(col, configured
                    ? "Linking takes a code typed on any browser; no keyboard is needed here."
                    : "Needs your own Google client, about ten minutes once. The steps are on "
                            + "the next screen.");
            button(col, "Link Google Drive", true, () -> host.open(DriveLinkActivity.class));
        }
    }

    private void notify(LinearLayout col) {
        if (model.input.notificationsAllowed) {
            value(col, "Allowed");
            para(col, "You will hear when a backup finishes with problems, and when scheduled "
                    + "backups keep failing and are paused.");
        } else {
            TextView v = value(col, "Off");
            v.setTextColor(Ui.color(host, R.color.warn));
            para(col, "A schedule that fails quietly is not a backup. Notifications are how "
                    + "EmuBackup tells you when something goes wrong while you are not looking.");
            button(col, "Allow notifications", true, () -> host.perform(Safety.Action.ALLOW_NOTIFICATIONS));
        }
    }

    private void advanced(LinearLayout col) {
        para(col, "For the person helping, not the person playing.");
        link(col, "Run history", "Every backup and restore this device ran, with what went wrong", this::history);
        link(col, "Every save folder", "Each place EmuBackup looks, with sizes", () -> host.open(TargetsActivity.class));
        link(col, "Status details", "Everything the app knows, for a bug report", () -> host.open(StatusActivity.class));
        link(col, "See the walkthrough again", null, () -> {
            Onboarding.reset(host);
            host.open(OnboardingActivity.class);
        });
        link(col, "About EmuBackup", version() + " \u00b7 Apache-2.0 \u00b7 github.com/tarikbc/emubackup", null);
    }

    private void link(LinearLayout col, String label, String hint, Runnable go) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.focus_ring);
        row.setPadding(Ui.dp(host, 18), Ui.dp(host, 12), Ui.dp(host, 18), Ui.dp(host, 12));
        row.setMinimumHeight(Ui.dp(host, 60));
        row.setFocusable(go != null);
        row.setClickable(go != null);
        if (go != null) row.setOnClickListener(v -> go.run());
        row.addView(Ui.text(host, label, 17, R.color.text_primary));
        if (hint != null) row.addView(Ui.text(host, hint, 14, R.color.text_secondary));
        col.addView(row, top(10));
    }

    private String version() {
        try {
            return "Version " + host.getPackageManager().getPackageInfo(host.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "Development build";
        }
    }

    private void history() {
        RunLog log = Prefs.log(host);
        List<RunLog.Run> runs = log.runs();
        StringBuilder b = new StringBuilder();
        if (runs.isEmpty()) {
            b.append("Nothing has run yet.");
        } else {
            SimpleDateFormat f = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
            int shown = Math.min(runs.size(), 10);
            for (int i = 0; i < shown; i++) {
                RunLog.Run r = runs.get(i);
                b.append(f.format(new Date(r.atMs))).append("  ").append(r.ok ? "ok      " : "failed  ")
                        .append(r.kind);
                if (r.versionId != null) b.append("  ").append(Sizes.human(r.bytes));
                b.append('\n');
                if (r.detail != null && !r.detail.isEmpty()) {
                    b.append("    ").append(r.detail.split("\n")[0]).append('\n');
                }
            }
            if (runs.size() > shown) b.append("and ").append(runs.size() - shown).append(" more\n");
        }
        // The secondary button shares rather than cancels, so the log can leave the device
        // without a screen of its own.
        showSheet("Run history", b.toString().trim(), "Share the full log", "OK", null, () -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "EmuBackup run log");
            i.putExtra(Intent.EXTRA_TEXT, Prefs.log(host).toPlainText());
            host.startActivity(Intent.createChooser(i, "Share the run log"));
        });
    }

    private static String capitalise(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- pane contract ----

    /** Opens a section from elsewhere, e.g. Home's "See what happened". */
    void open(Section s) {
        view();
        select(s, true);
    }

    @Override String[] legend() {
        return new String[] { "A", "Select", "B", "Back", "Y", "What is this?" };
    }

    @Override View defaultFocus() {
        view();
        return rows[selected.ordinal()];
    }

    @Override void help() {
        String body;
        switch (selected) {
            case WHERE: body = "Where each backup is written. Google Drive survives losing the device; "
                    + "a folder you pick is as safe as that place; this device only protects against "
                    + "an emulator wiping its saves."; break;
            case WHEN: body = "Whether backups run by themselves. A schedule only sends what changed, "
                    + "so daily costs seconds. The conditions keep it off mobile data and battery."; break;
            case WHAT: body = "Game saves are always in. Save states and emulator keys are extra: "
                    + "big, rebuildable, or not really yours."; break;
            case KEEP: body = "How far back you can go. Each backup adds only what changed, so many "
                    + "backups cost little until save states are included."; break;
            case ACCESS: body = "Storage access lets EmuBackup read other apps' folders. Extra access, "
                    + "through Shizuku, reaches the folders only an emulator can see."; break;
            case ACCOUNT: body = "The Google account that holds your backups on Drive. EmuBackup reads "
                    + "and writes its own folder and nothing else."; break;
            case NOTIFY: body = "How EmuBackup tells you a backup failed while you were not looking. "
                    + "Android turns this off until you allow it."; break;
            default: body = "Tools for finding out what happened and for reporting a bug. Nothing "
                    + "here is needed day to day.";
        }
        showSheet(selected.title, body, null, "OK", null);
    }

    @Override boolean back() {
        if (super.back()) return true;
        if (host.isTall() && detailOpen) {
            closeDetail();
            return true;
        }
        // Wide: B from inside the controls returns to the section list.
        View f = host.getCurrentFocus();
        if (f != null && detailHost != null && isIn(f, detailHost)) {
            Ui.focus(rows[selected.ordinal()], true);
            return true;
        }
        return false;
    }

    private static boolean isIn(View v, View ancestor) {
        android.view.ViewParent p = v.getParent();
        while (p != null) {
            if (p == ancestor) return true;
            p = p.getParent();
        }
        return false;
    }
}
