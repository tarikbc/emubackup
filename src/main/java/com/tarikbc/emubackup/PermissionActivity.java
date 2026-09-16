package com.tarikbc.emubackup;

import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Storage access and extra access, each with its state in plain words and one button.
 *
 * <p>Storage access is the permission without which nothing can be read. Extra access is
 * Shizuku, which reaches the folders only an emulator can see; it is optional, stops at every
 * reboot, and its state is re-checked on every resume because it can change underneath the
 * app. A missing grant never silently no-ops: each state names its way in. DESIGN.md §8.
 */
public class PermissionActivity extends GamepadActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout col;
    private LinearLayout shizukuCard;
    private TextView shizukuState, shizukuText;
    private TextView shizukuButton;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        root = new FrameLayout(this);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        io.execute(() -> {
            final ShizukuGate.Status st = ShizukuGate.connect(this);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                showShizuku(st);
            });
        });
    }

    @Override public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        render();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, dp);
        return lp;
    }

    private void render() {
        root.removeAllViews();
        boolean tall = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        ScrollView sv = new ScrollView(this);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int gx = Ui.dp(this, tall ? 20 : 32), gy = Ui.dp(this, 20);
        col.setPadding(gx, gy, gx, gy);
        sv.addView(col);
        root.addView(sv);
        col.addView(Ui.bold(this, "Storage and extra access", 24, R.color.text_primary));

        // ---- storage ----
        boolean allFiles = Permissions.hasAllFiles();
        LinearLayout a = card();
        TextView at = Ui.bold(this, "Storage access", 18, R.color.text_primary);
        Ui.iconStart(at, R.drawable.ic_folder, R.color.text_secondary, 20, 10);
        at.setGravity(android.view.Gravity.CENTER_VERTICAL);
        a.addView(at);
        a.addView(state(allFiles ? "Granted" : "Not granted", allFiles ? R.color.ok : R.color.danger));
        a.addView(Ui.text(this, "Emulator saves live in folders that belong to other apps. Android "
                + "hides those unless you allow EmuBackup to read all files. It reads only the "
                + "save folders it knows about, listed under Settings, and never your games.",
                15, R.color.text_secondary), top(8));
        if (!allFiles) {
            TextView b = Ui.primaryButton(this, "Grant storage access");
            b.setFocusedByDefault(true);
            b.setOnClickListener(v -> Permissions.requestAllFiles(this));
            LinearLayout.LayoutParams lp = top(14);
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            a.addView(b, lp);
            focusByDefault(b);
        }

        // ---- shizuku ----
        shizukuCard = card();
        TextView st = Ui.bold(this, "Extra access, with Shizuku", 18, R.color.text_primary);
        Ui.iconStart(st, R.drawable.ic_lock, R.color.text_secondary, 20, 10);
        st.setGravity(android.view.Gravity.CENTER_VERTICAL);
        shizukuCard.addView(st);
        shizukuState = state("Checking\u2026", R.color.text_secondary);
        shizukuCard.addView(shizukuState);
        shizukuText = Ui.text(this, "Some emulators keep saves in folders only they can see: "
                + "GameCube, Wii, PlayStation, 3DS, and Eden's profiles. Shizuku is a free app "
                + "that opens them without rooting the device. It is started once over a USB "
                + "cable or wireless debugging, and it stops at every reboot; EmuBackup says "
                + "when that matters. Everything else is backed up either way.",
                15, R.color.text_secondary);
        shizukuCard.addView(shizukuText, top(8));
        shizukuButton = null;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.card(this, R.color.surface));
        int p = Ui.dp(this, 20);
        c.setPadding(p, p, p, p);
        col.addView(c, top(14));
        return c;
    }

    private TextView state(String text, int colorRes) {
        TextView t = Ui.text(this, text, 17, colorRes);
        t.setLayoutParams(top(6));
        return t;
    }

    private void button(String label, Runnable go, boolean primary) {
        if (shizukuButton != null) shizukuCard.removeView(shizukuButton);
        shizukuButton = primary ? Ui.primaryButton(this, label) : Ui.secondaryButton(this, label);
        shizukuButton.setOnClickListener(v -> go.run());
        LinearLayout.LayoutParams lp = top(14);
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        shizukuCard.addView(shizukuButton, lp);
        if (Permissions.hasAllFiles()) {
            shizukuButton.setFocusedByDefault(true);
            focusByDefault(shizukuButton);
        }
    }

    private void showShizuku(ShizukuGate.Status st) {
        switch (st.state) {
            case READY:
                shizukuState.setText("Ready \u00b7 running as " + st.identity() + ", server v" + st.serverVersion);
                shizukuState.setTextColor(Ui.color(this, R.color.ok));
                return;
            case NOT_INSTALLED:
                shizukuState.setText("Not set up");
                shizukuState.setTextColor(Ui.color(this, R.color.text_secondary));
                button("Get Shizuku", () -> openUrl("https://shizuku.rikka.app/"), true);
                return;
            case NOT_RUNNING:
                shizukuState.setText("Installed but not running");
                shizukuState.setTextColor(Ui.color(this, R.color.warn));
                button("Open Shizuku", this::openShizuku, true);
                return;
            case NEEDS_PERMISSION:
                shizukuState.setText("Running; EmuBackup is not allowed yet");
                shizukuState.setTextColor(Ui.color(this, R.color.warn));
                button("Ask for access", ShizukuGate::requestPermission, true);
                return;
            case PROBE_FAILED:
            default:
                // Reported rather than retried silently. A privileged path that appears to work
                // and reads nothing produces an empty backup that looks successful.
                shizukuState.setText(st.detail == null ? "Could not be used on this device" : st.detail);
                shizukuState.setTextColor(Ui.color(this, R.color.danger));
                button("Try again", this::recreate, false);
        }
    }

    private void openShizuku() {
        Intent i = getPackageManager().getLaunchIntentForPackage(ShizukuGate.SHIZUKU_PACKAGE);
        if (i != null) startActivity(i);
        else openUrl("https://shizuku.rikka.app/");
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            // No browser. Nothing useful to fall back to, and the card already explains the step.
        }
    }
}
