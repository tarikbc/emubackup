package com.tarikbc.emubackup;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Writes one version out as a single ordinary zip, wherever the person chooses.
 *
 * <p>The store is already open and {@code RESTORE.txt} already explains how to recover from it
 * by hand. This is for the case where following instructions is the last thing someone wants to
 * be doing: one file, one unzip, the saves as they were, no chain and no app.
 */
public class ExportActivity extends Activity {

    public static final String EXTRA_VERSION = "version";
    private static final int REQ_CREATE = 1;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    private String versionId;
    private LinearLayout root;
    private TextView headline, detail;
    private ProgressBar bar;
    private Button action;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        versionId = getIntent().getStringExtra(EXTRA_VERSION);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(color(R.color.ink_black));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(root);
        setContentView(sv);

        headline = text("Export as one zip", 26, R.color.text_primary);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(headline);
        root.addView(text(versionId, 15, R.color.text_secondary));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        lp.topMargin = dp(24);
        root.addView(bar, lp);

        detail = text("Everything in this backup, merged into one file: no chain to follow and "
                + "nothing else to download. Every file is checked against its checksum on the "
                + "way in, so anything that does not match is left out rather than written "
                + "wrong.\n\nChoose where to save it.", 15, R.color.text_secondary);
        root.addView(detail);

        action = new Button(this);
        action.setText("Choose a location");
        action.setTextColor(color(R.color.ink_black));
        action.setBackgroundTintList(ColorStateList.valueOf(color(R.color.accent)));
        action.setOnClickListener(v -> pickDestination());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(28);
        root.addView(action, alp);
    }

    private void pickDestination() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, "emubackup-" + versionId + ".zip");
        try {
            startActivityForResult(i, REQ_CREATE);
        } catch (Exception e) {
            show(false, "No file picker", "This device has no way to choose a location, so the "
                    + "export cannot be saved. The backup itself is unaffected.");
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CREATE || result != RESULT_OK || data == null || data.getData() == null) return;
        start(data.getData());
    }

    private void start(Uri target) {
        action.setText("Stop");
        action.setBackgroundTintList(ColorStateList.valueOf(color(R.color.surface_high)));
        action.setTextColor(color(R.color.text_primary));
        action.setOnClickListener(v -> {
            cancelled = true;
            action.setEnabled(false);
        });
        bar.setVisibility(View.VISIBLE);
        detail.setText("Starting");

        io.execute(() -> {
            ExportRunner.Result r = null;
            String failure = null;
            try (OutputStream out = getContentResolver().openOutputStream(target)) {
                if (out == null) throw new java.io.IOException("could not open that location");
                r = ExportRunner.export(Stores.active(this), versionId, out,
                        new java.io.File(getCacheDir(), "export"),
                        new ExportRunner.Listener() {
                            @Override public void onProgress(String t, int done, int total) {
                                ui.post(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    if (total > 0) {
                                        bar.setIndeterminate(false);
                                        bar.setMax(total);
                                        bar.setProgress(done);
                                    }
                                    detail.setText(t == null ? "Finishing"
                                            : done + " of " + total + "  ·  " + t);
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
                    show(false, "Export failed", error);
                    return;
                }
                StringBuilder b = new StringBuilder(result.summary());
                for (String p : result.problems) b.append("\n\n").append(p);
                if (!result.ok() && !result.cancelled) {
                    b.append("\n\nThe backup itself is untouched. Check it from the restore "
                            + "screen to see whether the problem is in the stored copy.");
                }
                show(result.ok(), result.ok() ? "Exported" : result.cancelled ? "Stopped"
                        : "Exported with problems", b.toString());
            });
        });
    }

    private void show(boolean ok, String title, String body) {
        bar.setVisibility(View.GONE);
        headline.setText(title);
        headline.setTextColor(color(ok ? R.color.ok : R.color.danger));
        detail.setText(body);
        action.setText("Close");
        action.setEnabled(true);
        action.setOnClickListener(v -> finish());
    }

    private TextView text(String s, int sp, int colorRes) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color(colorRes));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLineSpacing(dp(4), 1f);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        io.shutdownNow();
    }

    private int color(int res) {
        return getResources().getColor(res, null);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
