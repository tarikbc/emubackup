package com.tarikbc.emubackup;

import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Live view of a running backup or restore.
 *
 * <p>Owns no work. {@link BackupService} runs it and keeps running whether or not this screen
 * exists, which is what lets a long run survive the screen turning off. This reads the latest
 * snapshot on resume and receives pushes while it is visible. B leaves the screen; the run
 * goes on and the result arrives as a notification.
 */
public class BackupActivity extends GamepadActivity implements BackupService.Listener {

    /** Set when this screen is showing a restore rather than a backup. */
    public static final String EXTRA_RESTORE = "restore";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean restoreMode;
    private RunView run;
    private boolean finished;
    private Progress last;
    private String finishText;
    private boolean finishFailed;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        restoreMode = getIntent().getBooleanExtra(EXTRA_RESTORE, false);
        build();
        // A restore is started by the sheet or preview that approved it. Only a plain backup
        // is kicked off from here.
        if (!restoreMode && !BackupService.RUNNING && BackupService.PROGRESS == null) {
            BackupService.start(this);
        }
    }

    private void build() {
        boolean tall = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        run = new RunView(this, restoreMode ? "Putting back" : "Backing up",
                restoreMode ? "A safety copy of anything replaced is made first."
                        : "Only what changed since the last backup is sent.", tall);
        run.onButton("Stop", () -> {
            BackupService.requestCancel();
            run.waiting("Stopping\u2026");
        });
        setContentView(run);
        setLegend("A", "Select", "B", "Hide");
        focusByDefault(run.button());
        if (finished) run.finish(!finishFailed, finishText != null && finishText.startsWith("Cancelled"),
                headline(finishFailed, finishText), finishText, this::finish);
        else if (last != null) render(last);
    }

    @Override public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        BackupService.LISTENER = this;
        Progress p = BackupService.PROGRESS;
        if (p != null) render(p);
    }

    @Override protected void onPause() {
        super.onPause();
        if (BackupService.LISTENER == this) BackupService.LISTENER = null;
    }

    @Override public void onProgress(Progress p) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            render(p);
        });
    }

    @Override public void onFinished(String text, boolean failed) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            finished = true;
            finishText = text;
            finishFailed = failed;
            boolean stopped = text != null && text.startsWith("Cancelled");
            run.finish(!failed && !stopped, stopped, headline(failed, text), text, this::finish);
            setLegend("A", "Close", "B", "Close");
        });
    }

    private String headline(boolean failed, String text) {
        boolean stopped = text != null && text.startsWith("Cancelled");
        if (restoreMode) return failed ? "Could not put it back" : stopped ? "Stopped" : "Put back";
        return failed ? "Backup failed" : stopped ? "Stopped" : "Backed up";
    }

    private void render(Progress p) {
        last = p;
        if (finished) return;
        switch (p.phase) {
            case SCANNING: run.phase("Looking at your saves"); break;
            case DIFFING: run.phase("Comparing with the last backup"); break;
            case ARCHIVING: run.phase(restoreMode ? "Putting back" : "Sending what changed"); break;
            case WRITING_MANIFEST: run.phase("Almost done"); break;
            case DONE: run.phase("Finished"); break;
            case CANCELLED: run.phase("Stopped"); break;
            case FAILED: run.phase("Failed"); break;
        }
        if (p.targetLabel != null) {
            run.item(p.targetCount > 0 ? p.targetLabel + " (" + p.targetIndex + " of " + p.targetCount + ")"
                    : p.targetLabel);
        } else if (p.phase == Progress.Phase.WRITING_MANIFEST) {
            run.item("Recording what was backed up");
        } else if (p.message != null) {
            run.item(p.message);
        }
        run.detail(p.fileName);
        int pc = p.percent();
        String countText = null;
        if (p.filesTotal > 0) countText = p.filesDone + " of " + p.filesTotal + " files";
        else if (p.bytesTotal > 0) countText = Sizes.human(p.bytesDone) + " of " + Sizes.human(p.bytesTotal);
        run.progress(pc < 0 ? -1 : pc, 100, countText);
    }
}
