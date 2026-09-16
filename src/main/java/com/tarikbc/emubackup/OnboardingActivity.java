package com.tarikbc.emubackup;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
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
 * The first run, as a sequence that leads rather than a hub that lists.
 *
 * <p>The hub was the first thing a new install showed: five buttons of equal weight, the largest
 * of them "Back up now", which was the wrong one to press because nothing had been granted yet.
 * The correct first tap, Permissions, was last.
 *
 * <p>The order here is deliberate. Access is asked for before anything is shown, because there is
 * nothing to show without it, and then the scan result is shown before anything else is asked
 * for. A number in the person's own saves is the only honest argument this app has, and it costs
 * nothing to make it before asking them to set up a Google client. Shizuku is offered after the
 * destination and never blocks, because it improves a backup that already works.
 */
public class OnboardingActivity extends Activity {

    private static final int STEP_WELCOME = 0;
    private static final int STEP_ACCESS = 1;
    private static final int STEP_FOUND = 2;
    private static final int STEP_WHERE = 3;
    private static final int STEP_LOCKED = 4;
    private static final int STEP_READY = 5;

    private static final int REQ_WHERE = 1;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private int step = STEP_WELCOME;
    private ScanSession scan;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(color(R.color.ink_black));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(36), dp(24), dp(28));
        sv.addView(root);
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        // Returning from the system settings page is the normal way this screen is re-entered,
        // so the access step re-checks rather than trusting what it knew when it was drawn.
        if (step == STEP_ACCESS && Permissions.hasAllFiles()) {
            step = STEP_FOUND;
        }
        render();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void render() {
        root.removeAllViews();
        switch (step) {
            case STEP_WELCOME: welcome(); break;
            case STEP_ACCESS:  access();  break;
            case STEP_FOUND:   found();   break;
            case STEP_WHERE:   where();   break;
            case STEP_LOCKED:  locked();  break;
            case STEP_READY:
            default:           ready();   break;
        }
    }

    // ---- steps ----

    private void welcome() {
        root.addView(heading("EmuBackup"));
        root.addView(lead("Emulator saves, backed up."));
        root.addView(body("Your saves are scattered across a dozen emulators, in folders that do "
                + "not agree on anything. Some of them are deleted when an emulator is "
                + "uninstalled or clears its own data.\n\n"
                + "EmuBackup finds all of them, keeps versions, and puts them back."));
        primary("Find my saves", () -> {
            step = Permissions.hasAllFiles() ? STEP_FOUND : STEP_ACCESS;
            render();
        });
    }

    private void access() {
        root.addView(heading("One permission"));
        root.addView(body("Emulator saves live in folders owned by other apps, at paths that "
                + "change per emulator. Android's scoped storage cannot reach them, so EmuBackup "
                + "needs all-files access.\n\n"
                + "It reads only the paths in its own registry, which ships inside the app and is "
                + "listed in full on the Saves screen. It never touches your ROMs."));
        primary("Grant access", () -> Permissions.requestAllFiles(this));
        secondary("Not now", this::skipToHub);
    }

    private void found() {
        root.addView(heading("Looking…"));
        root.addView(body("Reading every folder in the registry."));
        if (scan != null) {
            showFound();
            return;
        }
        io.execute(() -> {
            final ScanSession s = ScanSession.run(this);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                scan = s;
                if (step == STEP_FOUND) render();
            });
        });
    }

    private void showFound() {
        root.removeAllViews();
        if (!scan.ok()) {
            root.addView(heading("Something is wrong"));
            root.addView(body("The registry of save locations would not load, so nothing could be "
                    + "scanned. This is a bug, not something you can fix here."));
            root.addView(mono(scan.registryError));
            secondary("Continue anyway", this::skipToHub);
            return;
        }

        int found = scan.withContent();
        if (found == 0) {
            root.addView(heading("No saves found yet"));
            root.addView(body("None of the emulators EmuBackup knows about have saves on this "
                    + "device. That is normal on a new device. It will find them as you play, and "
                    + "the Saves screen lists every folder it looks in."));
            primary("Choose where backups go", () -> { step = STEP_WHERE; render(); });
            return;
        }

        root.addView(heading(Sizes.human(scan.coveredBytes()) + " of saves"));
        root.addView(lead(found + " save sets, across " + emulatorsWithData().size()
                + " emulators."));
        root.addView(mono(emulatorList()));
        root.addView(body("That is what would be backed up. Save states are excluded by default "
                + "because they are large and easy to recreate; you can turn them on later."));
        primary("Choose where backups go", () -> { step = STEP_WHERE; render(); });
    }

    private void where() {
        root.addView(heading("Where should they go?"));
        root.addView(body("A backup that lives on the same device as the saves protects you from "
                + "an emulator wiping its own data, and from nothing else. If you can send it "
                + "somewhere else, do.\n\n"
                + "The next screen has three choices, strongest first. You can change this at any "
                + "time, and changing it never deletes anything."));
        primary("Choose a destination", () ->
                startActivityForResult(new Intent(this, DestinationActivity.class), REQ_WHERE));
    }

    private void locked() {
        int n = scan == null ? 0 : scan.locked();
        root.addView(heading("Some saves are out of reach"));
        root.addView(lead(n + (n == 1 ? " save set is" : " save sets are")
                + " inside app-private folders."));
        root.addView(body("Android hides those from other apps. Shizuku is a separate free app "
                + "that grants the access without rooting the device. It has to be started again "
                + "after every reboot, and EmuBackup tells you when those saves are going stale."
                + "\n\nEverything else is backed up either way. This is worth doing later, not "
                + "now."));
        primary("Set up Shizuku", () ->
                startActivity(new Intent(this, PermissionActivity.class)));
        secondary("Skip for now", () -> { step = STEP_READY; render(); });
    }

    private void ready() {
        root.addView(heading("Ready"));
        String where;
        switch (Destination.effective(this)) {
            case DRIVE:  where = "Google Drive"; break;
            case FOLDER: where = Destination.folderLabel(this); break;
            default:     where = "a folder on this device"; break;
        }
        root.addView(lead("Backups go to " + where + "."));
        root.addView(body("The first one copies everything. After that only what changed is "
                + "written, so later backups are small and quick.\n\n"
                + "Every backup is a plain zip with a checksum file beside it. You can open one "
                + "by hand, without this app, on any machine."));
        primary("Back up now", () -> {
            Onboarding.markComplete(this);
            startActivity(new Intent(this, BackupActivity.class));
            finish();
        });
        secondary("Later", this::skipToHub);
    }

    // ---- helpers ----

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_WHERE) return;
        // The destination screen has no notion of success; whatever it left behind is the answer.
        step = (scan != null && scan.locked() > 0) ? STEP_LOCKED : STEP_READY;
        render();
    }

    private void skipToHub() {
        Onboarding.markComplete(this);
        finish();
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
                if (s != null && s.hasContent() && t.category == Category.SAVE) {
                    sum += s.totalBytes;
                }
            }
            if (sum > 0) bytes.put(e.label, sum);
        }
        List<Map.Entry<String, Long>> rows = new ArrayList<>(bytes.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Long> r : rows) {
            if (shown++ == 8) {
                sb.append("and ").append(rows.size() - 8).append(" more");
                break;
            }
            // Size first, right-aligned. Padding the name instead made the column collide with
            // labels like "Citra, legacy install (Nintendo 3DS)", which no sensible width fits.
            sb.append(rightAlign(Sizes.human(r.getValue()), 8))
              .append("  ").append(r.getKey()).append('\n');
        }
        return sb.toString().trim();
    }

    private static String rightAlign(String s, int width) {
        StringBuilder b = new StringBuilder();
        for (int i = s.length(); i < width; i++) b.append(' ');
        return b.append(s).toString();
    }

    // ---- views ----

    private void primary(String label, Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(color(R.color.ink_black));
        b.setBackgroundTintList(ColorStateList.valueOf(color(R.color.accent)));
        b.setOnClickListener(v -> onClick.run());
        root.addView(b, margin(dp(28)));
    }

    private void secondary(String label, Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(color(R.color.text_secondary));
        b.setBackgroundTintList(ColorStateList.valueOf(color(R.color.surface)));
        b.setOnClickListener(v -> onClick.run());
        root.addView(b, margin(dp(10)));
    }

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.text_primary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView lead(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.accent));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.text_secondary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setLineSpacing(dp(5), 1f);
        t.setPadding(0, dp(18), 0, 0);
        return t;
    }

    private TextView mono(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(R.color.text_secondary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLineSpacing(dp(4), 1f);
        t.setBackgroundColor(color(R.color.surface));
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        t.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout.LayoutParams margin(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private int color(int res) {
        return getResources().getColor(res, null);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Back leaves the walkthrough for the hub rather than trapping the person in it. */
    @Override public void onBackPressed() {
        skipToHub();
    }
}
