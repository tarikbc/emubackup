package com.tarikbc.emubackup;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The launcher: a rail of four places on the left, one pane on the right, the legend below.
 *
 * <p>Landscape handhelds are wide and short, so the app is a rail and a pane rather than a
 * stack of cards. The rail is also the status: its dot mirrors Home, so someone three screens
 * deep still knows whether their saves are safe.
 *
 * <p>Focus: D-pad left from a pane reaches the rail by ordinary focus search; A on a rail row
 * shows that pane and moves focus into it. B walks back one level: a sheet, then the pane's
 * own detail, then the rail, then Home, then out. L1/R1 step through the rail.
 */
public class ShellActivity extends GamepadActivity {

    enum Dest { HOME, GAMES, BACKUPS, SETTINGS }

    private static final int REQ_NOTIFICATIONS = 7;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout paneHost;
    private final TextView[] railRows = new TextView[Dest.values().length];
    private View dot;
    private TextView dotWord;
    private final Pane[] panes = new Pane[Dest.values().length];
    private Dest current = Dest.HOME;
    private HomeModel model;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(rail(), new LinearLayout.LayoutParams(Ui.dp(this, 150),
                ViewGroup.LayoutParams.MATCH_PARENT));
        paneHost = new FrameLayout(this);
        root.addView(paneHost, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setContentView(root);

        panes[Dest.HOME.ordinal()] = new HomePane(this);
        panes[Dest.GAMES.ordinal()] = new LinksPane(this, "Games",
                "Each game with its own history is coming here. For now, every save folder.",
                new LinksPane.Link("Every save folder", "What was found on this device",
                        () -> open(TargetsActivity.class)));
        panes[Dest.BACKUPS.ordinal()] = new LinksPane(this, "Backups", null,
                new LinksPane.Link("All backups", "Newest first. Put one back, check it, or export it",
                        () -> open(VersionsActivity.class)));
        panes[Dest.SETTINGS.ordinal()] = new LinksPane(this, "Settings", null,
                new LinksPane.Link("Where backups go", "Google Drive, a folder you choose, or this device",
                        () -> open(DestinationActivity.class)),
                new LinksPane.Link("When backups run", "Daily, weekly, or only when you ask",
                        () -> open(ScheduleActivity.class)),
                new LinksPane.Link("Storage and extra access", "What EmuBackup is allowed to read",
                        () -> open(PermissionActivity.class)),
                "Advanced",
                new LinksPane.Link("Every save folder", "Each place EmuBackup looks, with sizes",
                        () -> open(TargetsActivity.class)),
                new LinksPane.Link("Status details", "Everything the app knows, for a bug report",
                        () -> open(StatusActivity.class)),
                new LinksPane.Link("See the walkthrough again", null, () -> {
                    Onboarding.reset(this);
                    open(OnboardingActivity.class);
                }));

        show(Dest.HOME, false);

        // The shell stays the launcher and the walkthrough opens on top of it, so leaving the
        // walkthrough at any point lands here rather than on a blank task.
        if (!Onboarding.isComplete(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
    }

    private View rail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackgroundColor(Ui.color(this, R.color.surface_low));
        rail.setPadding(Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 16));

        TextView name = Ui.bold(this, "EmuBackup", 13, R.color.text_tertiary);
        name.setPadding(Ui.dp(this, 16), 0, 0, Ui.dp(this, 14));
        rail.addView(name);

        String[] labels = { "Home", "Games", "Backups", "Settings" };
        for (Dest d : Dest.values()) {
            TextView row = Ui.text(this, labels[d.ordinal()], 16, R.color.text_primary);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 8), 0);
            row.setBackgroundResource(R.drawable.rail_row);
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnClickListener(v -> show(d, true));
            railRows[d.ordinal()] = row;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
            lp.topMargin = Ui.dp(this, 2);
            rail.addView(row, lp);
        }

        rail.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(Ui.dp(this, 16), 0, 0, 0);
        dot = Ui.dot(this, R.color.text_tertiary, 10);
        status.addView(dot);
        dotWord = Ui.text(this, "Checking", 13, R.color.text_secondary);
        dotWord.setPadding(Ui.dp(this, 8), 0, 0, 0);
        status.addView(dotWord);
        rail.addView(status);
        return rail;
    }

    FrameLayout paneHost() {
        return paneHost;
    }

    private Pane pane() {
        return panes[current.ordinal()];
    }

    private void show(Dest d, boolean focusPane) {
        current = d;
        for (Dest x : Dest.values()) {
            TextView row = railRows[x.ordinal()];
            boolean on = x == d;
            row.setTextColor(Ui.color(this, on ? R.color.accent : R.color.text_primary));
            row.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        }
        paneHost.removeAllViews();
        Pane p = pane();
        paneHost.addView(p.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (d == Dest.HOME && model != null) ((HomePane) p).render(model);
        p.refresh();
        legendFor(p);
        if (focusPane) {
            View f = p.defaultFocus();
            focusByDefault(f != null ? f : paneHost);
        }
    }

    private void legendFor(Pane p) {
        String[] own = p.legend();
        String[] all = new String[own.length + 2];
        System.arraycopy(own, 0, all, 0, own.length);
        all[own.length] = "L1/R1";
        all[own.length + 1] = "Sections";
        setLegend(all);
    }

    // ---- data ----

    @Override protected void onResume() {
        super.onResume();
        dotWord.setText("Checking");
        io.execute(() -> {
            final HomeModel m = HomeModel.load(this);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                model = m;
                int hue = hueOf(m.report.state);
                ((android.graphics.drawable.GradientDrawable) dot.getBackground())
                        .setColor(Ui.color(this, hue));
                dotWord.setText(wordOf(m.report.state));
                dotWord.setTextColor(Ui.color(this, hue));
                HomePane home = (HomePane) panes[Dest.HOME.ordinal()];
                home.render(m);
                // Out of touch mode the framework hands initial focus to the first rail row
                // before anything has loaded. Once Home has an answer, the answer's button is
                // where a thumb should be resting.
                View f = getCurrentFocus();
                boolean parked = f == null || f == railRows[Dest.HOME.ordinal()];
                if (current == Dest.HOME && parked && GamepadActivity.gamepadPresent()) {
                    focusByDefault(home.defaultFocus());
                }
            });
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    static int hueOf(Safety.State s) {
        switch (s) {
            case SAFE: return R.color.ok;
            case ATTENTION: return R.color.warn;
            case PROBLEM: return R.color.danger;
            default: return R.color.text_tertiary;
        }
    }

    static String wordOf(Safety.State s) {
        switch (s) {
            case SAFE: return "Safe";
            case ATTENTION: return "Needs you";
            case PROBLEM: return "Problem";
            default: return "Not set up";
        }
    }

    // ---- actions ----

    void open(Class<?> activity) {
        startActivity(new Intent(this, activity));
    }

    void perform(Safety.Action a) {
        switch (a) {
            case START: open(OnboardingActivity.class); break;
            case BACK_UP_NOW: open(BackupActivity.class); break;
            case GRANT_STORAGE:
            case SET_UP_EXTRA_ACCESS: open(PermissionActivity.class); break;
            case CHOOSE_DESTINATION:
            case RECONNECT_DRIVE: open(DestinationActivity.class); break;
            case SEE_WHAT_HAPPENED: open(ScheduleActivity.class); break;
            case ALLOW_NOTIFICATIONS: askForNotifications(); break;
        }
    }

    /**
     * Android 13 makes notifications a runtime permission. The request dialog shows once; a
     * second denial is permanent and the only way back is the app's notification settings, so
     * that is where the second press goes. The escalation notification in BackupJobService is
     * the whole point of a schedule, and it is dropped silently without this.
     */
    private void askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) {
            openNotificationSettings();
            return;
        }
        SharedPreferences p = getSharedPreferences("shell", MODE_PRIVATE);
        boolean askedBefore = p.getBoolean("notif_asked", false);
        boolean canAsk = shouldShowRequestPermissionRationale("android.permission.POST_NOTIFICATIONS");
        if (askedBefore && !canAsk) {
            openNotificationSettings();
            return;
        }
        p.edit().putBoolean("notif_asked", true).apply();
        requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, REQ_NOTIFICATIONS);
    }

    private void openNotificationSettings() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        // onResume reloads either way; the point is not to cache a stale answer.
    }

    // ---- gamepad ----

    @Override protected void onGamepadL1() {
        Dest[] all = Dest.values();
        show(all[(current.ordinal() + all.length - 1) % all.length], true);
    }

    @Override protected void onGamepadR1() {
        Dest[] all = Dest.values();
        show(all[(current.ordinal() + 1) % all.length], true);
    }

    @Override protected void onGamepadY() {
        pane().help();
    }

    @Override public void onBackPressed() {
        if (pane().back()) return;
        View f = getCurrentFocus();
        boolean inPane = f != null && isDescendant(f, paneHost);
        if (inPane) {
            railRows[current.ordinal()].requestFocus();
            return;
        }
        if (current != Dest.HOME) {
            show(Dest.HOME, false);
            railRows[Dest.HOME.ordinal()].requestFocus();
            return;
        }
        super.onBackPressed();
    }

    private static boolean isDescendant(View v, View ancestor) {
        android.view.ViewParent p = v.getParent();
        while (p != null) {
            if (p == ancestor) return true;
            p = p.getParent();
        }
        return false;
    }
}
