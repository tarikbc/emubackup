package com.tarikbc.emubackup;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The schedule, what it keeps, and what it has actually done.
 *
 * <p>The history is on the same screen as the switch that turns the schedule on, deliberately.
 * A schedule you cannot audit is a promise, and the failure this app exists to prevent is the one
 * nobody noticed. The last outcome should be visible at the moment someone is thinking about
 * whether to rely on it.
 */
public class ScheduleActivity extends Activity {

    private static final long GB = 1024L * 1024L * 1024L;

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
        root.removeAllViews();
        Settings s = Prefs.settings(this);

        root.addView(heading("Automatic backups"));

        // --- when ---
        LinearLayout when = card(s.scheduled());
        when.addView(cardTitle("How often"));
        when.addView(body(s.describeSchedule()));
        row(when,
                choice("Off", s.frequency == Settings.Frequency.OFF,
                        () -> set(s.withFrequency(Settings.Frequency.OFF))),
                choice("Daily", s.frequency == Settings.Frequency.DAILY,
                        () -> set(s.withFrequency(Settings.Frequency.DAILY))),
                choice("Weekly", s.frequency == Settings.Frequency.WEEKLY,
                        () -> set(s.withFrequency(Settings.Frequency.WEEKLY))));

        if (s.scheduled()) {
            if (!BackupJobScheduler.isScheduled(this)) {
                // The setting and the framework disagreeing is worth saying out loud rather than
                // showing a schedule that is not registered.
                TextView warn = body("The system has no job registered for this. Toggle the "
                        + "frequency off and on to register it again.");
                warn.setTextColor(color(R.color.danger));
                when.addView(warn);
            }
            when.addView(body("The first backup copies everything and can be larger than the "
                    + "window the system gives a background job. Run that one by hand. After it, "
                    + "only what changed is sent, which finishes in seconds."));
        }

        // --- conditions ---
        LinearLayout cond = card(false);
        cond.addView(cardTitle("Only when"));
        row(cond,
                choice("On charge", s.requiresCharging, () -> set(s.withCharging(!s.requiresCharging))),
                choice("On Wi-Fi", s.requiresUnmetered, () -> set(s.withUnmetered(!s.requiresUnmetered))));
        cond.addView(body("A full backup is hundreds of megabytes and a full scan is not free. "
                + "Turning both off lets a backup run on mobile data, on battery."));

        // --- what to include ---
        LinearLayout what = card(s.includeStates || s.includeKeys);
        what.addView(cardTitle("What to include"));
        what.addView(body("Game saves are always included. These two are not, and the sizes are "
                + "from the last scan of this device."));
        row(what,
                choice("Save states" + sizeSuffix(Category.STATE), s.includeStates,
                        () -> set(s.withStates(!s.includeStates))),
                choice("Emulator keys" + sizeSuffix(Category.KEY), s.includeKeys,
                        () -> set(s.withKeys(!s.includeKeys))));
        what.addView(body("Save states are large, tied to one emulator build, and recreated by "
                + "playing. Keys are not save data and are not always yours to copy. Both are "
                + "off until you say otherwise."));

        // --- retention ---
        LinearLayout keep = card(false);
        keep.addView(cardTitle("How many to keep"));
        keep.addView(body(s.keepVersions == RetentionPolicy.UNLIMITED
                ? "Every backup is kept."
                : s.keepVersions + " backups. Older ones are removed after a successful run."));
        row(keep,
                choice("5", s.keepVersions == 5, () -> set(s.withKeep(5))),
                choice("20", s.keepVersions == 20, () -> set(s.withKeep(20))),
                choice("50", s.keepVersions == 50, () -> set(s.withKeep(50))),
                choice("All", s.keepVersions == RetentionPolicy.UNLIMITED,
                        () -> set(s.withKeep(RetentionPolicy.UNLIMITED))));
        keep.addView(body("A backup that another one still builds on is never removed, and "
                + "neither is the snapshot taken before a restore."));

        // --- size ceiling ---
        LinearLayout cap = card(s.budgetBytes != RetentionPolicy.UNLIMITED);
        cap.addView(cardTitle("Total size"));
        cap.addView(body(s.budgetBytes == RetentionPolicy.UNLIMITED
                ? "No limit. The count above is the only thing removing old backups."
                : "At most " + Sizes.human(s.budgetBytes)
                        + ". The oldest go once the store is larger than this."));
        row(cap,
                choice("2 GB", s.budgetBytes == 2L * GB, () -> set(s.withBudget(2L * GB))),
                choice("10 GB", s.budgetBytes == 10L * GB, () -> set(s.withBudget(10L * GB))),
                choice("50 GB", s.budgetBytes == 50L * GB, () -> set(s.withBudget(50L * GB))),
                choice("No limit", s.budgetBytes == RetentionPolicy.UNLIMITED,
                        () -> set(s.withBudget(RetentionPolicy.UNLIMITED))));
        if (s.includeStates && s.budgetBytes == RetentionPolicy.UNLIMITED) {
            // Save states are the only thing here big enough to make a count-based limit
            // dangerous. On this device they are 496 MB, so twenty versions of them is ten
            // gigabytes of someone's Drive quota spent without being asked.
            TextView warn = body("Save states are on and there is no size limit. "
                    + Sizes.human(ScanSession.lastKnownBytes(Category.STATE))
                    + " per full backup, kept " + (s.keepVersions == RetentionPolicy.UNLIMITED
                            ? "forever" : s.keepVersions + " deep")
                    + ", is a lot of storage to use without deciding to.");
            warn.setTextColor(color(R.color.warn));
            cap.addView(warn);
        }
        cap.addView(body("The newest backup is never removed to meet this, so a limit smaller "
                + "than one backup does not empty the store."));

        // --- history ---
        RunLog log = Prefs.log(this);
        List<RunLog.Run> runs = log.runs();
        LinearLayout hist = card(log.consecutiveFailures() == 0);
        hist.addView(cardTitle("History"));
        if (runs.isEmpty()) {
            hist.addView(body("Nothing has run yet."));
        } else {
            if (log.consecutiveFailures() > 0) {
                TextView bad = body(log.consecutiveFailures() + " scheduled "
                        + (log.consecutiveFailures() == 1 ? "run has" : "runs have")
                        + " failed in a row.");
                bad.setTextColor(color(R.color.danger));
                hist.addView(bad);
            }
            hist.addView(mono(recent(runs)));
            addButton(hist, "Share the full log", this::share);
        }
    }

    /**
     * "  ·  496 MB" for a category with data, or nothing.
     *
     * <p>Read from the cached scan rather than rescanning: this screen should open instantly, and
     * a stale size on a toggle is a smaller sin than a second of blank screen. Absent is better
     * than wrong, so nothing is shown when there is no scan to quote.
     */
    private String sizeSuffix(Category category) {
        long bytes = ScanSession.lastKnownBytes(category);
        return bytes <= 0 ? "" : "\n" + Sizes.human(bytes);
    }

    private String recent(List<RunLog.Run> runs) {
        SimpleDateFormat f = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        StringBuilder b = new StringBuilder();
        int shown = Math.min(runs.size(), 8);
        for (int i = 0; i < shown; i++) {
            RunLog.Run r = runs.get(i);
            b.append(f.format(new Date(r.atMs)))
             .append(r.ok ? "  ok    " : "  failed ")
             .append(r.kind);
            if (r.versionId != null) b.append("  ").append(Sizes.human(r.bytes));
            b.append('\n');
            if (!r.ok && !r.detail.isEmpty()) {
                b.append("   ").append(r.detail.split("\n")[0]).append('\n');
            }
        }
        if (runs.size() > shown) b.append("and ").append(runs.size() - shown).append(" more");
        return b.toString().trim();
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "EmuBackup run log");
        i.putExtra(Intent.EXTRA_TEXT, Prefs.log(this).toPlainText());
        startActivity(Intent.createChooser(i, "Share the run log"));
    }

    private void set(Settings s) {
        Prefs.save(this, s);
        recreate();
    }

    // ---- views ----

    private LinearLayout card(boolean active) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(color(R.color.surface));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));

        View hue = new View(this);
        hue.setBackgroundColor(color(active ? R.color.accent : R.color.text_tertiary));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.addView(hue, new LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        root.addView(wrap, lp);
        return c;
    }

    private void row(LinearLayout card, View... items) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : items) {
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6);
            r.addView(v, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.addView(r, lp);
    }

    private Button choice(String label, boolean on, Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(color(on ? R.color.ink_black : R.color.text_primary));
        b.setBackgroundTintList(ColorStateList.valueOf(color(on ? R.color.accent : R.color.surface_high)));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private void addButton(LinearLayout card, String label, Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(color(R.color.text_primary));
        b.setBackgroundTintList(ColorStateList.valueOf(color(R.color.surface_high)));
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.addView(b, lp);
    }

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.text_primary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView cardTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.text_primary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.text_secondary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setLineSpacing(dp(4), 1f);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private TextView mono(String s) {
        TextView t = body(s);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return t;
    }

    private int color(int res) {
        return getResources().getColor(res, null);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
