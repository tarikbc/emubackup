package com.tarikbc.emubackup;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The hub.
 *
 * <p>Rescans on every resume rather than caching, because both capabilities this app depends on
 * can disappear between visits: all-files access can be revoked in Settings, and Shizuku does
 * not survive a reboot. A stale "you are covered" is the failure mode the app exists to prevent.
 *
 * <p>Concurrency follows {@code ARCHITECTURE.md} section 8: one executor, results posted to the
 * main looper, every callback re-checking that the Activity is still alive.
 */
public class MainActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView headline, subline, detail;
    private View statusHue;
    private SizeBarView bar;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);

        headline = findViewById(R.id.headline);
        subline = findViewById(R.id.subline);
        detail = findViewById(R.id.detail);
        statusHue = findViewById(R.id.status_hue);
        bar = findViewById(R.id.bar);

        ((Button) findViewById(R.id.btn_backup)).setOnClickListener(
                v -> startActivity(new Intent(this, BackupActivity.class)));
        ((Button) findViewById(R.id.btn_targets)).setOnClickListener(
                v -> startActivity(new Intent(this, TargetsActivity.class)));
        ((Button) findViewById(R.id.btn_versions)).setOnClickListener(
                v -> startActivity(new Intent(this, VersionsActivity.class)));
        ((Button) findViewById(R.id.btn_permissions)).setOnClickListener(
                v -> startActivity(new Intent(this, PermissionActivity.class)));
    }

    @Override protected void onResume() {
        super.onResume();
        headline.setText(R.string.scanning);
        subline.setText("");
        io.execute(() -> {
            final ScanSession s = ScanSession.run(this);
            postIfAlive(() -> render(s));
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void postIfAlive(Runnable r) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            r.run();
        });
    }

    private void render(ScanSession s) {
        if (!s.ok()) {
            hue(R.color.danger);
            headline.setText("Registry failed to load");
            subline.setText("No targets could be read, so nothing would be backed up.");
            detail.setText(s.registryError);
            return;
        }
        if (!s.caps.allFiles) {
            hue(R.color.warn);
            headline.setText("All-files access needed");
            subline.setText("Without it EmuBackup cannot read any of your saves.");
            bar.setBytes(0, 0, 0, 0);
            detail.setText(describe(s));
            return;
        }

        long saves = s.bytesOf(Category.SAVE);
        long states = s.bytesOf(Category.STATE);
        long keys = s.bytesOf(Category.KEY);
        long config = s.bytesOf(Category.CONFIG);
        bar.setBytes(saves, states, keys, config);

        int total = s.registry.allTargets().size();
        int found = s.withContent();
        int locked = s.locked();

        hue(s.problems().isEmpty() ? R.color.ok : R.color.danger);
        headline.setText(Sizes.human(s.coveredBytes()) + " of saves found");
        subline.setText(found + " of " + total + " save sets have data"
                + (locked > 0 ? " · " + locked + " need Shizuku" : ""));
        detail.setText(describe(s));
    }

    private void hue(int colorRes) {
        statusHue.setBackgroundColor(getResources().getColor(colorRes, null));
    }

    private String describe(ScanSession s) {
        StringBuilder b = new StringBuilder();
        b.append("all-files   ").append(s.caps.allFiles ? "granted" : "NOT granted").append('\n');
        b.append("shizuku     ").append(s.caps.appPrivate
                ? "ready (" + s.shizuku.identity() + ", server v" + s.shizuku.serverVersion + ")"
                : s.shizuku.state.name().toLowerCase(java.util.Locale.ROOT)).append('\n');
        if (!s.caps.appPrivate && s.shizuku.detail != null) {
            b.append("            ").append(s.shizuku.detail).append('\n');
        }
        b.append("drive       ").append(s.caps.drive ? "configured" : "local only").append('\n');
        if (s.registry != null) {
            b.append("registry    v").append(s.registry.registryVersion())
                    .append(", ").append(s.registry.emulators().size()).append(" emulators, ")
                    .append(s.registry.allTargets().size()).append(" targets\n");
            b.append("saves       ").append(Sizes.human(s.bytesOf(Category.SAVE))).append('\n');
            b.append("states      ").append(Sizes.human(s.bytesOf(Category.STATE))).append(" (opt-in)\n");
            b.append("keys        ").append(Sizes.human(s.bytesOf(Category.KEY))).append(" (opt-in)\n");
            for (String w : s.registry.warnings()) b.append("warning     ").append(w).append('\n');
        }
        for (TargetScan p : s.problems()) {
            b.append("PROBLEM     ").append(p.targetId).append(": ").append(p.status)
                    .append(p.detail == null ? "" : " — " + p.detail).append('\n');
        }
        return b.toString().trim();
    }
}
