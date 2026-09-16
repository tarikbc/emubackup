package com.tarikbc.emubackup;

import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads every archive a backup needs and checks each checksum.
 *
 * <p>The only answer this app can give about a backup that restoring does not already imply.
 * On a Drive backup it means downloading it, which is the only way to know the bytes are still
 * there. The outcome is remembered per backup in {@link VerifyMemory}.
 */
public class VerifyActivity extends GamepadActivity {

    public static final String EXTRA_VERSION = "version";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private String versionId;
    private RunView run;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        versionId = getIntent().getStringExtra(EXTRA_VERSION);
        boolean tall = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        run = new RunView(this, "Checking this backup",
                "Every archive it needs is read and every file's checksum compared. On Google "
                        + "Drive that means downloading it.", tall);
        run.onButton("Stop", () -> {
            cancelled = true;
            run.waiting("Stopping\u2026");
        });
        setContentView(run);
        focusByDefault(run.button());
        start();
    }

    private void start() {
        io.execute(() -> {
            VerifyRunner.Result r;
            String failure = null;
            try {
                r = VerifyRunner.verify(Stores.active(this), versionId, new VerifyRunner.Listener() {
                    @Override public void onProgress(String archive, int done, int total) {
                        ui.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            run.item(archive == null ? "Finishing" : archive);
                            run.progress(total > 0 ? done : -1, total,
                                    total > 0 ? done + " of " + total + " archives" : null);
                        });
                    }

                    @Override public boolean isCancelled() { return cancelled; }
                });
            } catch (Exception e) {
                r = null;
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final VerifyRunner.Result result = r;
            final String error = failure;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (error != null) {
                    run.finish(false, false, "Could not be checked", error, this::finish);
                    return;
                }
                // A finished check is worth remembering; a stopped one proves nothing.
                if (!result.cancelled) {
                    VerifyMemory.record(this, versionId, result.ok(), result.summary());
                }
                StringBuilder b = new StringBuilder(result.summary());
                for (String p : result.problems) b.append("\n\n").append(p);
                if (!result.ok() && !result.cancelled) {
                    b.append("\n\nThe saves on your device are untouched. Make a fresh backup, "
                            + "and keep this one until you have.");
                }
                run.finish(result.ok(), result.cancelled, result.ok() ? "This backup is sound"
                        : result.cancelled ? "Stopped" : "This backup is not sound", b.toString(), this::finish);
                setLegend("A", "Close", "B", "Close");
            });
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        io.shutdownNow();
    }
}
