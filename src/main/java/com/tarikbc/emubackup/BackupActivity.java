package com.tarikbc.emubackup;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Live view of a running backup.
 *
 * <p>Owns no work. {@link BackupService} runs the backup and keeps running whether or not this
 * screen exists, which is what lets a long run survive the screen turning off or the Activity
 * being destroyed on rotation. This reads the latest snapshot on resume and receives pushes
 * while it is visible.
 */
public class BackupActivity extends Activity implements BackupService.Listener {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView phase, target, file, summary;
    private ProgressBar bar;
    private Button action;
    private boolean finished;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_backup);
        phase = findViewById(R.id.phase);
        target = findViewById(R.id.target);
        file = findViewById(R.id.file);
        summary = findViewById(R.id.summary);
        bar = findViewById(R.id.bar);
        action = findViewById(R.id.action);

        action.setOnClickListener(v -> {
            if (finished) {
                finish();
            } else {
                BackupService.requestCancel();
                action.setEnabled(false);
                phase.setText("Cancelling…");
            }
        });

        if (!BackupService.RUNNING && BackupService.PROGRESS == null) {
            BackupService.start(this);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        BackupService.LISTENER = this;
        Progress p = BackupService.PROGRESS;
        if (p != null) render(p);
    }

    @Override protected void onPause() {
        super.onPause();
        // Cleared so the service never holds a reference to a screen that is gone.
        if (BackupService.LISTENER == this) BackupService.LISTENER = null;
    }

    @Override public void onProgress(Progress p) {
        postIfAlive(() -> render(p));
    }

    @Override public void onFinished(String text, boolean failed) {
        postIfAlive(() -> {
            finished = true;
            summary.setVisibility(View.VISIBLE);
            summary.setText(text);
            summary.setTextColor(getResources().getColor(
                    failed ? R.color.danger : R.color.ok, null));
            action.setText(R.string.close);
            action.setEnabled(true);
            bar.setIndeterminate(false);
            bar.setProgress(failed ? 0 : 100);
        });
    }

    private void postIfAlive(Runnable r) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            r.run();
        });
    }

    private void render(Progress p) {
        switch (p.phase) {
            case SCANNING: phase.setText("Scanning"); break;
            case DIFFING: phase.setText("Comparing"); break;
            case ARCHIVING: phase.setText("Archiving"); break;
            case WRITING_MANIFEST: phase.setText("Writing manifest"); break;
            case DONE: phase.setText("Finished"); break;
            case CANCELLED: phase.setText("Cancelled"); break;
            case FAILED: phase.setText("Failed"); break;
        }

        if (p.targetLabel != null) {
            String t = p.targetLabel;
            if (p.targetCount > 0) t += "  (" + p.targetIndex + " of " + p.targetCount + ")";
            target.setText(t);
        } else if (p.message != null) {
            target.setText(p.message);
        }

        file.setText(p.fileName == null ? "" : p.fileName);

        int pc = p.percent();
        if (pc < 0) {
            bar.setIndeterminate(true);
        } else {
            bar.setIndeterminate(false);
            bar.setProgress(pc);
        }
    }
}
