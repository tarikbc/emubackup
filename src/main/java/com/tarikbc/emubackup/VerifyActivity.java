package com.tarikbc.emubackup;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads a stored version back and checks it against what was recorded.
 *
 * <p>The result is deliberately blunt in both directions. A pass says how many archives were read
 * and how many bytes, because "verified" with no number behind it is the kind of reassurance this
 * app exists to distrust. A failure names the archive and what is wrong with it, because a person
 * who learns their backup is broken needs to know which part, not that a check went red.
 */
public class VerifyActivity extends Activity {

    public static final String EXTRA_VERSION = "version";

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

        headline = text("Checking this backup", 26, R.color.text_primary);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(headline);
        root.addView(text(versionId, 15, R.color.text_secondary));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        lp.topMargin = dp(24);
        root.addView(bar, lp);

        detail = text("Reading every archive this version needs. On a Drive backup that means "
                + "downloading it, which is the only way to know the bytes are still there.",
                15, R.color.text_secondary);
        root.addView(detail);

        action = new Button(this);
        action.setText("Stop");
        action.setTextColor(color(R.color.text_primary));
        action.setBackgroundTintList(ColorStateList.valueOf(color(R.color.surface_high)));
        action.setOnClickListener(v -> {
            cancelled = true;
            action.setEnabled(false);
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(28);
        root.addView(action, alp);

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
                            if (total > 0) {
                                bar.setIndeterminate(false);
                                bar.setMax(total);
                                bar.setProgress(done);
                            }
                            detail.setText(archive == null
                                    ? "Finishing"
                                    : done + " of " + total + "  ·  " + archive);
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
                if (error != null) show(false, "Could not be checked", error);
                else {
                    // A finished check is worth remembering; a stopped one proves nothing.
                    if (!result.cancelled) {
                        VerifyMemory.record(VerifyActivity.this, versionId, result.ok(), result.summary());
                    }
                    show(result.ok(), result.ok() ? "This backup is sound"
                            : result.cancelled ? "Stopped" : "This backup is not sound",
                            report(result));
                }
            });
        });
    }

    private String report(VerifyRunner.Result r) {
        StringBuilder b = new StringBuilder(r.summary());
        for (String p : r.problems) b.append("\n\n").append(p);
        if (!r.ok() && !r.cancelled) {
            b.append("\n\nThe saves on your device are untouched. Make a fresh backup, and keep "
                    + "this one until you have.");
        }
        return b.toString();
    }

    private void show(boolean ok, String title, String body) {
        bar.setVisibility(android.view.View.GONE);
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
