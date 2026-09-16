package com.tarikbc.emubackup;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
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
 * The launcher: four places, one pane, the legend. DESIGN.md §5.
 *
 * <p>Two forms. Wide (landscape) puts the places on a rail to the left of the pane. Tall
 * (portrait) puts the same four places in a tab bar under the pane, with the legend between.
 * The form is chosen from the configuration and rebuilt on rotation without reloading
 * anything, because a rotation must not cost a rescan or a trip to Drive.
 *
 * <p>Focus: D-pad left from a pane reaches the rail by ordinary focus search; A on a place
 * shows that pane and moves focus into it. B walks back one level: a sheet, the pane's own
 * detail, the rail, Home, out. L1/R1 step through the places.
 */
public class ShellActivity extends GamepadActivity {

    enum Dest { HOME, GAMES, BACKUPS, SETTINGS }

    private static final String[] LABELS = { "Home", "Games", "Backups", "Settings" };
    private static final int[] ICONS = { R.drawable.ic_house, R.drawable.ic_gamepad_2,
            R.drawable.ic_archive, R.drawable.ic_settings };
    private static final int REQ_NOTIFICATIONS = 7;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean tall;
    private FrameLayout paneHost;
    private final TextView[] placeRows = new TextView[Dest.values().length];
    private final android.graphics.drawable.Drawable[] tabIcons =
            new android.graphics.drawable.Drawable[Dest.values().length];
    private View dot;
    private final Pane[] panes = new Pane[Dest.values().length];
    private Dest current = Dest.HOME;
    private HomeModel model;
    /** True while a gamepad button (not a navigation key) is driving a focus change. */
    private boolean keyDriven;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        panes[Dest.HOME.ordinal()] = new HomePane(this);
        panes[Dest.GAMES.ordinal()] = new GamesPane(this);
        panes[Dest.BACKUPS.ordinal()] = new BackupsPane(this);
        panes[Dest.SETTINGS.ordinal()] = new SettingsPane(this);

        build();
        show(Dest.HOME, false);

        // The shell stays the launcher and the walkthrough opens on top of it, so leaving the
        // walkthrough at any point lands here rather than on a blank task.
        if (!Onboarding.isComplete(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
    }

    boolean isTall() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    /** Builds the shell for the current form. Safe to call again after a rotation. */
    private void build() {
        tall = isTall();
        paneHost = new FrameLayout(this);
        if (tall) {
            setShell(paneHost, tabBar());
        } else {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(rail(), new LinearLayout.LayoutParams(Ui.dp(this, 150),
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(paneHost, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            setContentView(root);
        }
        if (model != null) renderStatus(model);
    }

    @Override public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (isTall() == tall) return;
        for (Pane p : panes) if (p != null) p.invalidateView();
        build();
        show(current, false);
    }

    private TextView placeRow(Dest d, int sp) {
        TextView row = Ui.text(this, LABELS[d.ordinal()], sp, R.color.text_primary);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.rail_row);
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(v -> show(d, true));
        placeRows[d.ordinal()] = row;
        return row;
    }

    private View rail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackgroundColor(Ui.color(this, R.color.surface_low));
        rail.setPadding(Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 16));

        TextView name = Ui.bold(this, "EmuBackup", 13, R.color.text_secondary);
        name.setPadding(Ui.dp(this, 16), 0, 0, Ui.dp(this, 14));
        name.setGravity(Gravity.CENTER_VERTICAL);
        Ui.iconStart(name, R.drawable.ic_mark, R.color.accent, 18, 8);
        rail.addView(name);

        for (Dest d : Dest.values()) {
            TextView row = placeRow(d, 16);
            row.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 12), 0);
            Ui.iconStart(row, ICONS[d.ordinal()], R.color.text_secondary, 22, 12);
            if (d == Dest.HOME) {
                // The status rides on Home as a dot. The word is on Home itself.
                dot = Ui.dot(this, R.color.text_tertiary, 8);
                android.graphics.drawable.Drawable dd = dot.getBackground();
                dd.setBounds(0, 0, Ui.dp(this, 8), Ui.dp(this, 8));
                row.setCompoundDrawablesRelative(row.getCompoundDrawablesRelative()[0], null, dd, null);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
            lp.topMargin = Ui.dp(this, 2);
            rail.addView(row, lp);
        }

        return rail;
    }

    /** The tall form's places. The status dot rides on Home, because Home is the status. */
    private View tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Ui.color(this, R.color.surface_low));
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        for (Dest d : Dest.values()) {
            TextView row = placeRow(d, 12);
            row.setGravity(Gravity.CENTER);
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
            // Icon above the label, the phone idiom. The status dot rides on Home's right.
            android.graphics.drawable.Drawable ic = getDrawable(ICONS[d.ordinal()]).mutate();
            ic.setBounds(0, 0, Ui.dp(this, 22), Ui.dp(this, 22));
            android.graphics.drawable.Drawable dd = null;
            if (d == Dest.HOME) {
                dot = Ui.dot(this, R.color.text_tertiary, 8);
                dd = dot.getBackground();
                dd.setBounds(0, 0, Ui.dp(this, 8), Ui.dp(this, 8));
            }
            row.setCompoundDrawablePadding(Ui.dp(this, 4));
            row.setCompoundDrawables(null, ic, dd, null);
            tabIcons[d.ordinal()] = ic;
            FrameLayout slot = new FrameLayout(this);
            slot.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    Ui.dp(this, 56), Gravity.CENTER));
            bar.addView(slot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    FrameLayout paneHost() {
        return paneHost;
    }

    HomeModel model() {
        return model;
    }

    boolean keyDriven() {
        return keyDriven;
    }

    ExecutorService io() {
        return io;
    }

    Handler ui() {
        return ui;
    }

    /** A pane's state changed in a way that changes what the buttons mean. */
    void refreshLegend() {
        legendFor(pane());
    }

    /** Hands an approved plan to the service and shows its progress. */
    void startRestore(BackupService.RestoreRequest request) {
        BackupService.startRestore(this, request);
        Intent i = new Intent(this, BackupActivity.class);
        i.putExtra(BackupActivity.EXTRA_RESTORE, true);
        startActivity(i);
    }

    private Pane pane() {
        return panes[current.ordinal()];
    }

    private void show(Dest d, boolean focusPane) {
        current = d;
        for (Dest x : Dest.values()) {
            TextView row = placeRows[x.ordinal()];
            boolean on = x == d;
            int hue = on ? R.color.accent : R.color.text_primary;
            row.setTextColor(Ui.color(this, hue));
            row.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            if (tall) {
                if (tabIcons[x.ordinal()] != null) tabIcons[x.ordinal()].setTint(Ui.color(this, hue));
            } else {
                android.graphics.drawable.Drawable keep = row.getCompoundDrawablesRelative()[2];
                Ui.iconStart(row, ICONS[x.ordinal()], on ? R.color.accent : R.color.text_secondary, 22, 12);
                row.setCompoundDrawablesRelative(row.getCompoundDrawablesRelative()[0], null, keep, null);
            }
        }
        paneHost.removeAllViews();
        Pane p = pane();
        paneHost.addView(p.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.refresh();
        legendFor(p);
        if (focusPane) {
            // Requested now, before the next frame: with the old pane gone and nothing focused,
            // the framework would park focus on the first focusable view, the Home row, and
            // its ring would flash until a posted request moved it. A pane that returns null
            // has arranged its own focus (a list that must lay out first) and hands over its
            // list so the parking spot is a view that draws no ring.
            View f = p.defaultFocus();
            if (f != null) {
                Ui.focus(f, keyDriven);
                focusByDefault(f);
            }
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
        reload();
    }

    /** Rescans and re-reads the store. Every resume does this; a retry button does it too. */
    void reload() {
        io.execute(() -> {
            final HomeModel m = HomeModel.load(this);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                model = m;
                renderStatus(m);
                for (Pane p : panes) if (p != null) p.onModel(m);
                // Out of touch mode the framework hands initial focus to the first place
                // before anything has loaded. Once Home has an answer, the answer's button is
                // where a thumb should be resting.
                View f = getCurrentFocus();
                boolean parked = f == null || f == placeRows[Dest.HOME.ordinal()];
                if (current == Dest.HOME && parked && GamepadActivity.gamepadPresent()) {
                    focusByDefault(pane().defaultFocus());
                }
            });
        });
    }

    private void renderStatus(HomeModel m) {
        int hue = hueOf(m.report.state);
        ((android.graphics.drawable.GradientDrawable) dot.getBackground()).setColor(Ui.color(this, hue));
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
            case SEE_WHAT_HAPPENED:
                show(Dest.SETTINGS, false);
                ((SettingsPane) panes[Dest.SETTINGS.ordinal()]).open(SettingsPane.Section.ADVANCED);
                break;
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
        keyDriven = true;
        show(all[(current.ordinal() + all.length - 1) % all.length], true);
        keyDriven = false;
    }

    @Override protected void onGamepadR1() {
        Dest[] all = Dest.values();
        keyDriven = true;
        show(all[(current.ordinal() + 1) % all.length], true);
        keyDriven = false;
    }

    @Override protected void onGamepadY() {
        pane().help();
    }

    @Override protected void onGamepadX() {
        pane().onX();
    }

    @Override public void onBackPressed() {
        if (pane().back()) return;
        View f = getCurrentFocus();
        boolean inPane = f != null && isDescendant(f, paneHost);
        if (inPane) {
            placeRows[current.ordinal()].requestFocusFromTouch();
            return;
        }
        if (current != Dest.HOME) {
            show(Dest.HOME, false);
            placeRows[Dest.HOME.ordinal()].requestFocusFromTouch();
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
