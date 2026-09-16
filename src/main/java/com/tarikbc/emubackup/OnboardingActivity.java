package com.tarikbc.emubackup;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The first run: seven beats, one button each. DESIGN.md §5 "The walkthrough".
 *
 * <p>Access is asked for before anything is shown, because there is nothing to show without
 * it; then the scan result is shown before anything else is asked for, because a number in the
 * person's own saves is the only honest argument this app has. Extra access is offered and
 * never blocks. Notifications come last, with the reason: a schedule that fails quietly is not
 * a backup. B at any beat lands on Home; the walkthrough is then done and can be seen again
 * from Settings.
 *
 * <p>Wide: one large line on the left, two sentences and the button on the right. Tall: one
 * column. Rotation re-renders the beat; nothing is re-asked.
 */
public class OnboardingActivity extends GamepadActivity {

    private static final int STEP_WELCOME = 0;
    private static final int STEP_ACCESS = 1;
    private static final int STEP_FOUND = 2;
    private static final int STEP_WHERE = 3;
    private static final int STEP_LOCKED = 4;
    private static final int STEP_NOTIFY = 5;
    private static final int STEP_READY = 6;

    private static final int REQ_WHERE = 1;
    private static final int REQ_NOTIFY = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ViewGroup frame;
    private int step = STEP_WELCOME;
    private ScanSession scan;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) step = saved.getInt("step", STEP_WELCOME);
        frame = new android.widget.FrameLayout(this);
        setContentView(frame);
        setLegend("A", "Select", "B", "Back");
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
    }

    @Override public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        // Returning from the system settings page is the normal way this screen is re-entered,
        // so the access beat re-checks rather than trusting what it knew when it was drawn.
        if (step == STEP_ACCESS && Permissions.hasAllFiles()) step = STEP_FOUND;
        if (step == STEP_NOTIFY && notificationsAllowed()) step = STEP_READY;
        render();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override public void onBackPressed() {
        // Leaving at any beat is a choice, not an accident: Home is behind this screen and
        // everything here can be done again from Settings.
        Onboarding.markComplete(this);
        finish();
    }

    private boolean isTall() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private boolean notificationsAllowed() {
        return getSystemService(android.app.NotificationManager.class).areNotificationsEnabled();
    }

    // ---- one beat ----

    private LinearLayout left, right;
    private View primaryButton;

    private void beat(String hero, String sub) {
        frame.removeAllViews();
        boolean tall = isTall();
        left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = Ui.bold(this, hero, tall ? 32 : 40, R.color.text_primary);
        left.addView(h);
        if (sub != null) left.addView(Ui.text(this, sub, 17, R.color.accent), top(8));

        right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);

        if (tall) {
            ScrollView sv = new ScrollView(this);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dp(this, 24), Ui.dp(this, 32), Ui.dp(this, 24), Ui.dp(this, 24));
            col.addView(left);
            col.addView(right, top(20));
            sv.addView(col);
            frame.addView(sv);
        } else {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Ui.dp(this, 48), Ui.dp(this, 24), Ui.dp(this, 48), Ui.dp(this, 24));
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            rlp.leftMargin = Ui.dp(this, 40);
            ScrollView sv = new ScrollView(this);
            sv.addView(right);
            row.addView(sv, rlp);
            frame.addView(row);
        }
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, dp);
        return lp;
    }

    private void say(String text) {
        right.addView(Ui.text(this, text, 16, R.color.text_secondary), top(right.getChildCount() == 0 ? 0 : 12));
    }

    private void mono(String text) {
        TextView t = Ui.text(this, text, 13, R.color.text_primary);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        right.addView(t, top(12));
    }

    private void primary(String label, Runnable go) {
        TextView b = Ui.primaryButton(this, label);
        b.setFocusedByDefault(true);
        b.setOnClickListener(v -> go.run());
        LinearLayout.LayoutParams lp = top(24);
        lp.width = isTall() ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        right.addView(b, lp);
        primaryButton = b;
        focusByDefault(b);
    }

    private void secondary(String label, Runnable go) {
        TextView b = Ui.secondaryButton(this, label);
        b.setOnClickListener(v -> go.run());
        LinearLayout.LayoutParams lp = top(10);
        lp.width = isTall() ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        right.addView(b, lp);
    }

    // ---- the beats ----

    private void render() {
        switch (step) {
            case STEP_WELCOME: welcome(); break;
            case STEP_ACCESS: access(); break;
            case STEP_FOUND: found(); break;
            case STEP_WHERE: where(); break;
            case STEP_LOCKED: locked(); break;
            case STEP_NOTIFY: notifications(); break;
            default: ready(); break;
        }
    }

    private void welcome() {
        beat("Your saves, safe.", "EmuBackup");
        say("Emulator saves are scattered across a dozen apps, in folders that do not agree on "
                + "anything. Some vanish when an emulator is uninstalled or clears its data.");
        say("EmuBackup finds all of them, keeps every version, and puts any one of them back.");
        primary("Find my saves", () -> {
            step = Permissions.hasAllFiles() ? STEP_FOUND : STEP_ACCESS;
            render();
        });
    }

    private void access() {
        beat("One permission.", "Storage access");
        say("Saves live in folders that belong to other apps. Android hides those unless you "
                + "allow EmuBackup to read all files.");
        say("It reads only the save folders it knows about, listed in full under Settings. It "
                + "never touches your games.");
        primary("Grant storage access", () -> Permissions.requestAllFiles(this));
        secondary("Not now", this::onBackPressed);
    }

    private void found() {
        if (scan == null) {
            beat("Looking\u2026", null);
            say("Reading every save folder EmuBackup knows about.");
            io.execute(() -> {
                final ScanSession s = ScanSession.run(this);
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    scan = s;
                    if (step == STEP_FOUND) render();
                });
            });
            return;
        }
        if (!scan.ok()) {
            beat("Something is wrong.", null);
            say("The list of save locations would not load, so nothing could be looked at. "
                    + "This is a bug in the app, not something to fix here.");
            mono(scan.registryError);
            primary("Continue anyway", this::onBackPressed);
            return;
        }
        int found = scan.withContent();
        if (found == 0) {
            beat("No saves yet.", null);
            say("None of the emulators EmuBackup knows about have saves on this device. That is "
                    + "normal on a new one; they will be found as you play.");
            primary("Choose where backups go", () -> { step = STEP_WHERE; render(); });
            return;
        }
        beat(Sizes.human(scan.coveredBytes()), "of saves, in " + emulatorsWithData().size()
                + (emulatorsWithData().size() == 1 ? " emulator" : " emulators"));
        mono(emulatorList());
        say("That is what gets backed up. Save states are left out until you turn them on, "
                + "because they are large and the game recreates them.");
        primary("Choose where backups go", () -> { step = STEP_WHERE; render(); });
    }

    private void where() {
        beat("Where?", "Where backups go");
        say("A backup kept on this device protects you from an emulator wiping its own saves, "
                + "and from nothing else. If you can send it somewhere else, do.");
        say("Google Drive is strongest. A folder you pick, such as an SD card, is next. You can "
                + "change this at any time, and changing it never deletes anything.");
        primary("Choose where backups go", () ->
                startActivityForResult(new Intent(this, DestinationActivity.class), REQ_WHERE));
    }

    private void locked() {
        int n = scan == null ? 0 : scan.locked();
        beat(n + (n == 1 ? " folder" : " folders"), "need extra access");
        say("Some emulators keep saves in folders only they can see: GameCube, Wii, "
                + "PlayStation, 3DS. Shizuku is a free app that opens them, started once over "
                + "a cable; it stops at every reboot, and EmuBackup says when that matters.");
        say("Everything else is backed up either way. This is worth doing later, not now.");
        primary("Set up extra access", () -> startActivity(new Intent(this, PermissionActivity.class)));
        secondary("Skip for now", () -> { step = STEP_NOTIFY; render(); });
    }

    private void notifications() {
        beat("Tell me if it fails.", "Notifications");
        say("A backup that fails quietly is not a backup. Notifications are how EmuBackup "
                + "tells you a scheduled backup did not happen, while you are not looking.");
        say("Nothing else is ever sent. No reminders, no news.");
        primary("Allow notifications", () -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, REQ_NOTIFY);
            } else {
                step = STEP_READY;
                render();
            }
        });
        secondary("Not now", () -> { step = STEP_READY; render(); });
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_NOTIFY) {
            // Granted or not, the answer was given; Settings says which and offers the fix.
            step = STEP_READY;
            render();
        }
    }

    private void ready() {
        String where;
        switch (Destination.effective(this)) {
            case DRIVE: where = "Google Drive"; break;
            case FOLDER: where = Destination.folderLabel(this); break;
            default: where = "this device"; break;
        }
        beat("Ready.", "Backups go to " + where);
        say("The first backup copies everything and can take a few minutes. After that only "
                + "what changed is sent, in seconds.");
        say("Every backup is plain files you can open by hand on any computer, without this app.");
        primary("Back up now", () -> {
            Onboarding.markComplete(this);
            startActivity(new Intent(this, BackupActivity.class));
            finish();
        });
        secondary("Later", this::onBackPressed);
    }

    // ---- helpers ----

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_WHERE) return;
        // The destination screen has no notion of success; whatever it left behind is the answer.
        if (scan != null && scan.locked() > 0) step = STEP_LOCKED;
        else step = notificationsAllowed() ? STEP_READY : STEP_NOTIFY;
        render();
    }

    /** Emulator labels that have at least one target with data, in registry order. */
    private List<String> emulatorsWithData() {
        List<String> out = new ArrayList<>();
        if (scan == null || scan.registry == null) return out;
        for (Emulator e : scan.registry.emulators()) {
            for (Target t : e.targets) {
                TargetScan s = scan.scanOf(t.id);
                if (s != null && s.hasContent()) {
                    out.add(e.label);
                    break;
                }
            }
        }
        return out;
    }

    /** Each emulator with data, and how much, biggest first. */
    private String emulatorList() {
        Map<String, Long> bytes = new LinkedHashMap<>();
        for (Emulator e : scan.registry.emulators()) {
            long sum = 0;
            for (Target t : e.targets) {
                TargetScan s = scan.scanOf(t.id);
                if (s != null && s.hasContent() && t.category == Category.SAVE) sum += s.totalBytes;
            }
            if (sum > 0) bytes.put(e.label, sum);
        }
        List<Map.Entry<String, Long>> rows = new ArrayList<>(bytes.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Long> r : rows) {
            if (shown++ == 6) {
                sb.append("and ").append(rows.size() - 6).append(" more");
                break;
            }
            String name = r.getKey();
            int cut = name.indexOf(" (");
            if (cut > 0) name = name.substring(0, cut);
            sb.append(rightAlign(Sizes.human(r.getValue()), 8)).append("  ").append(name).append('\n');
        }
        return sb.toString().trim();
    }

    private static String rightAlign(String s, int width) {
        StringBuilder b = new StringBuilder();
        for (int i = s.length(); i < width; i++) b.append(' ');
        return b.append(s).toString();
    }
}
