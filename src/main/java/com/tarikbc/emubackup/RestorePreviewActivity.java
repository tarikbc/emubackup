package com.tarikbc.emubackup;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The dry run before putting a whole backup back. There is no path from a backup to a write
 * that does not pass through here.
 *
 * <p>Restore is the one operation in this app that can destroy data, and the failure it must
 * prevent is putting an older backup over progress made since. So files newer on the device
 * are counted per save folder, left alone by default, and replacing them is a per-folder
 * choice taken with the count and the age of the newest one in view. Confirmations are
 * in-pane sheets whose safe button takes focus first. See {@code ARCHITECTURE.md} section 6.
 */
public class RestorePreviewActivity extends GamepadActivity {

    public static final String EXTRA_VERSION = "version";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String versionId;
    private RestoreSession session;
    private final List<RestorePlan> plans = new ArrayList<>();

    private FrameLayout root;
    private TextView title, subtitle;
    private RecyclerView list;
    private TextView action;
    private SheetView sheet;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        versionId = getIntent().getStringExtra(EXTRA_VERSION);
        build();
        io.execute(() -> {
            final RestoreSession s = RestoreSession.load(this, versionId);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                render(s);
            });
        });
    }

    private boolean tall() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private void build() {
        root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int gx = Ui.dp(this, tall() ? 20 : 32), gy = Ui.dp(this, 20);
        col.setPadding(gx, gy, gx, 0);

        title = Ui.bold(this, "Put back everything", 24, R.color.text_primary);
        col.addView(title);
        subtitle = Ui.text(this, "Comparing the backup with what is on the device\u2026", 14, R.color.text_secondary);
        col.addView(subtitle, top(4));

        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        action = Ui.primaryButton(this, "Put it back");
        action.setEnabled(false);
        action.setAlpha(0.4f);
        action.setFocusedByDefault(true);
        action.setOnClickListener(v -> confirm());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                tall() ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.bottomMargin = gy;
        col.addView(action, alp);

        root.addView(col);
        setContentView(root);
        setLegend("A", "Select", "B", "Back");
        if (session != null) render(session);
    }

    @Override public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        build();
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, dp);
        return lp;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override public void onBackPressed() {
        if (sheet != null && sheet.isShowing()) {
            sheet.dismiss();
            sheet = null;
            return;
        }
        super.onBackPressed();
    }

    private void render(RestoreSession s) {
        session = s;
        if (!s.ok()) {
            subtitle.setText("Could not read this backup: " + s.error);
            subtitle.setTextColor(Ui.color(this, R.color.danger));
            return;
        }
        if (plans.isEmpty()) plans.addAll(s.plans);
        long at = s.manifest == null ? 0 : s.manifest.createdAtMs;
        if (at > 0) title.setText("Put back everything from " + When.format(at, System.currentTimeMillis()));
        list.setAdapter(new Adapter());
        updateSummary();
        focusByDefault(action.isEnabled() ? action : list);
    }

    private void updateSummary() {
        int write = 0, conflicts = 0, blocked = 0;
        long bytes = 0;
        for (RestorePlan p : plans) {
            write += p.toWrite().size();
            bytes += p.bytesToWrite();
            if (!p.forced) conflicts += p.count(RestoreAction.CONFLICT_NEWER);
            blocked += p.count(RestoreAction.BLOCKED_TIER);
        }
        StringBuilder b = new StringBuilder();
        if (write == 0) b.append("Nothing to write: your saves already match this backup");
        else b.append(write).append(write == 1 ? " file to write, " : " files to write, ").append(Sizes.human(bytes));
        if (conflicts > 0) b.append(" \u00b7 ").append(conflicts).append(" newer on this device, left alone");
        if (blocked > 0) b.append(" \u00b7 ").append(blocked).append(" need extra access");
        subtitle.setText(b.toString());
        subtitle.setTextColor(Ui.color(this, R.color.text_secondary));
        action.setEnabled(write > 0);
        action.setAlpha(write > 0 ? 1f : 0.4f);
    }

    // ---- confirm ----

    private void confirm() {
        int appPrivate = 0, forcedFiles = 0;
        for (RestorePlan p : plans) {
            if (p.tier == Tier.APP_PRIVATE) appPrivate += p.toWrite().size();
            if (p.forced) forcedFiles += p.count(RestoreAction.CONFLICT_NEWER);
        }
        StringBuilder body = new StringBuilder("A safety copy of anything replaced is made first, "
                + "so this can be undone. Files you added since then are left in place.");
        if (appPrivate > 0) {
            body.append("\n\n").append(appPrivate).append(appPrivate == 1 ? " file goes" : " files go")
                    .append(" into folders only the emulators can see, written through Shizuku. "
                            + "The emulator may need relaunching to notice.");
        }
        String primary = "Put it back";
        if (forcedFiles > 0) {
            body.append("\n\n").append(forcedFiles).append(forcedFiles == 1 ? " file" : " files")
                    .append(" on this device ").append(forcedFiles == 1 ? "is" : "are")
                    .append(" newer than the backup and will be replaced, as you chose.");
            primary = "Replace them anyway";
        }
        sheet = SheetView.show(root, "Put back everything?", body.toString(), "Cancel", primary, this::start);
    }

    private void start() {
        List<RestorePlan> toRun = new ArrayList<>();
        for (RestorePlan p : plans) if (!p.toWrite().isEmpty()) toRun.add(p);
        BackupService.startRestore(this, new BackupService.RestoreRequest(versionId, toRun));
        Intent i = new Intent(this, BackupActivity.class);
        i.putExtra(BackupActivity.EXTRA_RESTORE, true);
        startActivity(i);
        finish();
    }

    // ---- rows ----

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout v = new LinearLayout(RestorePreviewActivity.this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setBackgroundResource(R.drawable.focus_ring);
            v.setPadding(Ui.dp(RestorePreviewActivity.this, 18), Ui.dp(RestorePreviewActivity.this, 12),
                    Ui.dp(RestorePreviewActivity.this, 18), Ui.dp(RestorePreviewActivity.this, 12));
            v.setMinimumHeight(Ui.dp(RestorePreviewActivity.this, 60));
            v.setFocusable(true);
            v.setClickable(true);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(RestorePreviewActivity.this, 10);
            v.setLayoutParams(lp);
            LinearLayout head = new LinearLayout(RestorePreviewActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(Ui.text(RestorePreviewActivity.this, "", 17, R.color.text_primary),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(Ui.bold(RestorePreviewActivity.this, "", 17, R.color.accent));
            v.addView(head);
            v.addView(Ui.text(RestorePreviewActivity.this, "", 14, R.color.text_secondary));
            v.addView(Ui.text(RestorePreviewActivity.this, "", 14, R.color.warn));
            return new Holder(v);
        }

        @Override public void onBindViewHolder(Holder h, int position) {
            RestorePlan p = plans.get(position);
            h.label.setText(p.targetLabel);
            int write = p.toWrite().size();
            h.count.setText(write == 0 ? "" : write + (write == 1 ? " file" : " files"));

            StringBuilder d = new StringBuilder();
            add(d, p.count(RestoreAction.CREATE), "new");
            add(d, p.count(RestoreAction.OVERWRITE_OLDER), "replaced");
            add(d, p.count(RestoreAction.SIZE_CONFLICT), "same date, different content");
            add(d, p.count(RestoreAction.SKIP_IDENTICAL), "already the same");
            add(d, p.count(RestoreAction.ORPHAN_ON_DEVICE), "only on this device, left alone");
            if (d.length() == 0) d.append("nothing to do");
            h.detail.setText(d.toString());

            int conflicts = p.count(RestoreAction.CONFLICT_NEWER);
            int blocked = p.count(RestoreAction.BLOCKED_TIER);
            if (blocked > 0) {
                h.warning.setVisibility(View.VISIBLE);
                h.warning.setTextColor(Ui.color(RestorePreviewActivity.this, R.color.text_tertiary));
                h.warning.setText(blocked + (blocked == 1 ? " file needs" : " files need")
                        + " extra access; set it up under Settings first.");
                h.itemView.setOnClickListener(null);
                h.itemView.setFocusable(false);
            } else if (conflicts > 0) {
                h.warning.setVisibility(View.VISIBLE);
                h.warning.setTextColor(Ui.color(RestorePreviewActivity.this, p.forced ? R.color.danger : R.color.warn));
                String age = Ago.format(p.newestConflictMtime(), System.currentTimeMillis());
                h.warning.setText(p.forced
                        ? "Will replace " + conflicts + (conflicts == 1 ? " newer file" : " newer files")
                          + ", the newest from " + age + ". Press to leave them alone."
                        : conflicts + (conflicts == 1 ? " file" : " files") + " on this device "
                          + (conflicts == 1 ? "is" : "are") + " newer, the newest from " + age
                          + ". Left alone. Press to replace them.");
                h.itemView.setFocusable(true);
                h.itemView.setOnClickListener(v -> {
                    plans.set(position, p.withForced(!p.forced));
                    notifyItemChanged(position);
                    updateSummary();
                });
            } else {
                h.warning.setVisibility(View.GONE);
                h.itemView.setOnClickListener(null);
                h.itemView.setFocusable(true);
            }
        }

        private void add(StringBuilder b, int n, String label) {
            if (n <= 0) return;
            if (b.length() > 0) b.append(" \u00b7 ");
            b.append(n).append(' ').append(label);
        }

        @Override public int getItemCount() {
            return plans.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView label, count, detail, warning;

        Holder(LinearLayout v) {
            super(v);
            LinearLayout head = (LinearLayout) v.getChildAt(0);
            label = (TextView) head.getChildAt(0);
            count = (TextView) head.getChildAt(1);
            detail = (TextView) v.getChildAt(1);
            warning = (TextView) v.getChildAt(2);
        }
    }
}
