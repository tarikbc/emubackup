package com.tarikbc.emubackup;

import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Writes one backup out as a single ordinary zip, wherever the person chooses.
 *
 * <p>The store is already open and {@code RESTORE.txt} explains how to recover from it by
 * hand. This is for the case where following instructions is the last thing someone wants to
 * be doing: one file, one unzip, the saves as they were, no chain and no app.
 */
public class ExportActivity extends GamepadActivity {

    public static final String EXTRA_VERSION = "version";
    private static final int REQ_CREATE = 1;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private String versionId;
    private RunView run;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        versionId = getIntent().getStringExtra(EXTRA_VERSION);
        boolean tall = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        run = new RunView(this, "Export as one file",
                "Everything in this backup, merged into one zip: nothing else to download, no "
                        + "app needed to open it. Every file is checked on the way in; anything "
                        + "that does not match is left out rather than written wrong.", tall);
        run.progress(-1, 0, null);
        run.primary(true);
        run.onButton("Choose where to save it", this::pickDestination);
        setContentView(run);
        focusByDefault(run.button());
    }

    private void pickDestination() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, "emubackup-" + versionId + ".zip");
        try {
            startActivityForResult(i, REQ_CREATE);
        } catch (Exception e) {
            run.finish(false, false, "No file picker", "This device has no way to choose a "
                    + "location, so the export cannot be saved. The backup itself is unaffected.", this::finish);
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CREATE || result != RESULT_OK || data == null || data.getData() == null) return;
        start(data.getData());
    }

    private void start(Uri target) {
        run.primary(false);
        run.onButton("Stop", () -> {
            cancelled = true;
            run.waiting("Stopping\u2026");
        });
        run.phase("Exporting");
        run.item("Starting");
        io.execute(() -> {
            ExportRunner.Result r = null;
            String failure = null;
            try (OutputStream out = getContentResolver().openOutputStream(target)) {
                if (out == null) throw new java.io.IOException("could not open that location");
                r = ExportRunner.export(Stores.active(this), versionId, out,
                        new java.io.File(getCacheDir(), "export"), new ExportRunner.Listener() {
                            @Override public void onProgress(String t, int done, int total) {
                                ui.post(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    run.item(t == null ? "Finishing" : t);
                                    run.progress(total > 0 ? done : -1, total,
                                            total > 0 ? done + " of " + total + " save folders" : null);
                                });
                            }

                            @Override public boolean isCancelled() { return cancelled; }
                        });
            } catch (Exception e) {
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final ExportRunner.Result result = r;
            final String error = failure;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (error != null) {
                    run.finish(false, false, "Export failed", error, this::finish);
                    return;
                }
                StringBuilder b = new StringBuilder(result.summary());
                for (String p : result.problems) b.append("\n\n").append(p);
                if (!result.ok() && !result.cancelled) {
                    b.append("\n\nThe backup itself is untouched. Check it from Backups to see "
                            + "whether the problem is in the stored copy.");
                }
                run.finish(result.ok(), result.cancelled, result.ok() ? "Exported"
                        : result.cancelled ? "Stopped" : "Exported with problems", b.toString(), this::finish);
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
