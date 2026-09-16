package com.tarikbc.emubackup;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The status block, verbatim: every capability, path and count the app knows about, in the
 * register a bug report needs. Under Advanced, because it is for the person helping, not
 * the person playing.
 */
public class StatusActivity extends GamepadActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView body;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView sv = new ScrollView(this);
        sv.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20));
        body = new TextView(this);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setTextColor(Ui.color(this, R.color.text_primary));
        body.setText("scanning\u2026");
        body.setTextIsSelectable(true);
        sv.addView(body);
        setContentView(sv);
        setLegend("B", "Back");
        focusByDefault(sv);
        sv.setFocusable(true);
    }

    @Override protected void onResume() {
        super.onResume();
        io.execute(() -> {
            final ScanSession s = ScanSession.run(this);
            final String text = describe(s);
            ui.post(() -> {
                if (!isFinishing() && !isDestroyed()) body.setText(text);
            });
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private String scheduleState() {
        Settings s = Prefs.settings(this);
        RunLog log = Prefs.log(this);
        String base = s.describeSchedule();
        if (log.consecutiveFailures() > 0) {
            return base + " \u00b7 " + log.consecutiveFailures() + " failed in a row";
        }
        RunLog.Run last = log.last();
        return last == null ? base : base + " \u00b7 last run " + (last.ok ? "ok" : "FAILED");
    }

    private String destinationState() {
        switch (Destination.effective(this)) {
            case DRIVE:
                return "Google Drive";
            case FOLDER:
                return Destination.folderLabel(this);
            case DEVICE:
            default:
                return Stores.localRoot().getAbsolutePath()
                        + (Destination.chosenButUnavailable(this) ? "  (fallback)" : "");
        }
    }

    private String describe(ScanSession s) {
        StringBuilder b = new StringBuilder();
        if (!s.ok()) {
            b.append("REGISTRY FAILED  ").append(s.registryError).append('\n');
        }
        b.append("all-files   ").append(s.caps.allFiles ? "granted" : "NOT granted").append('\n');
        b.append("shizuku     ").append(s.caps.appPrivate
                ? "ready (" + s.shizuku.identity() + ", server v" + s.shizuku.serverVersion + ")"
                : s.shizuku.state.name().toLowerCase(java.util.Locale.ROOT)).append('\n');
        if (!s.caps.appPrivate && s.shizuku.detail != null) {
            b.append("            ").append(s.shizuku.detail).append('\n');
        }
        b.append("backups to  ").append(destinationState()).append('\n');
        b.append("schedule    ").append(scheduleState()).append('\n');
        if (s.registry != null) {
            b.append("registry    v").append(s.registry.registryVersion())
                    .append(", ").append(s.registry.emulators().size()).append(" emulators, ")
                    .append(s.registry.allTargets().size()).append(" targets\n");
            b.append("saves       ").append(Sizes.human(s.bytesOf(Category.SAVE))).append('\n');
            Settings set = Prefs.settings(this);
            b.append("states      ").append(Sizes.human(s.bytesOf(Category.STATE)))
                    .append(set.includeStates ? " (included)\n" : " (not included)\n");
            b.append("keys        ").append(Sizes.human(s.bytesOf(Category.KEY)))
                    .append(set.includeKeys ? " (included)\n" : " (not included)\n");
            b.append("found       ").append(s.withContent()).append(" of ")
                    .append(s.registry.allTargets().size()).append(" targets have data, ")
                    .append(s.locked()).append(" locked\n");
            for (String w : s.registry.warnings()) b.append("warning     ").append(w).append('\n');
        }
        if (!"none".equals(s.overrideStatus)) {
            b.append("override    ").append(s.overrideStatus).append('\n');
        }
        for (TargetScan p : s.problems()) {
            b.append("PROBLEM     ").append(p.targetId).append(": ").append(p.status)
                    .append(p.detail == null ? "" : " \u2014 " + p.detail).append('\n');
        }
        return b.toString().trim();
    }
}
