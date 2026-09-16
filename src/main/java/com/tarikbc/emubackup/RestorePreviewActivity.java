package com.tarikbc.emubackup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The dry run. There is no path from a backup version to a write that does not pass through here.
 *
 * <p>Restore is the one operation in this app that can destroy data, and the specific failure it
 * must prevent is restoring an older backup over progress made since. So files that are newer on
 * the device are counted separately, left alone by default, and overriding them is a per-target
 * decision taken with the count and the age of the newest one in view.
 *
 * <p>See {@code ARCHITECTURE.md} section 6.
 */
public class RestorePreviewActivity extends Activity {

    public static final String EXTRA_VERSION = "version";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String versionId;
    private RestoreSession session;
    private final List<RestorePlan> plans = new ArrayList<>();

    private TextView subtitle;
    private RecyclerView list;
    private Button action;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        versionId = getIntent().getStringExtra(EXTRA_VERSION);

        setContentView(R.layout.activity_list);
        ((TextView) findViewById(R.id.title)).setText(R.string.restore_title);
        subtitle = findViewById(R.id.subtitle);
        subtitle.setText(R.string.scanning);

        LinearLayout container = findViewById(R.id.container);
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        container.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        action = new Button(this);
        action.setText(R.string.restore_action);
        action.setEnabled(false);
        action.setTextColor(getResources().getColor(R.color.ink_black, null));
        action.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.accent, null)));
        action.setOnClickListener(v -> confirmAndRun());
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad / 2, pad, pad);
        container.addView(action, lp);

        io.execute(() -> {
            final RestoreSession s = RestoreSession.load(this, versionId);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                render(s);
            });
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void render(RestoreSession s) {
        session = s;
        if (!s.ok()) {
            subtitle.setText("Could not read this backup: " + s.error);
            return;
        }
        plans.clear();
        plans.addAll(s.plans);
        list.setAdapter(new Adapter());
        updateSummary();
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
        b.append(versionId).append("  ·  ").append(write).append(" files to write (")
                .append(Sizes.human(bytes)).append(')');
        if (conflicts > 0) b.append("  ·  ").append(conflicts).append(" newer on device, skipped");
        if (blocked > 0) b.append("  ·  ").append(blocked).append(" need Shizuku");
        subtitle.setText(b.toString());
        action.setEnabled(write > 0);
    }

    private void confirmAndRun() {
        int appPrivateFiles = 0;
        for (RestorePlan p : plans) {
            if (p.tier == Tier.APP_PRIVATE) appPrivateFiles += p.toWrite().size();
        }
        if (appPrivateFiles > 0) {
            // Writing into another app's private directory is the riskiest thing this app does.
            // Files created by the shell user have to stay readable by the emulator that owns the
            // folder. That is expected to hold, but it is unproven on any given device, so it gets
            // its own confirmation until a real round trip has been verified here.
            new AlertDialog.Builder(this)
                    .setTitle("Restore into app-private storage?")
                    .setMessage(appPrivateFiles + " file(s) go into folders owned by the emulators "
                            + "themselves, written through Shizuku.\n\nThis part is not yet proven "
                            + "on this device. A copy of anything replaced is saved first, and the "
                            + "emulator may need relaunching afterwards.\n\nShared-storage saves are "
                            + "unaffected either way.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Continue", (d, w) -> confirmConflicts())
                    .show();
            return;
        }
        confirmConflicts();
    }

    private void confirmConflicts() {
        int forcedFiles = 0;
        for (RestorePlan p : plans) if (p.forced) forcedFiles += p.count(RestoreAction.CONFLICT_NEWER);

        if (forcedFiles == 0) {
            start();
            return;
        }
        // Overwriting newer work needs a deliberate act, and the non-default button.
        new AlertDialog.Builder(this)
                .setTitle("Overwrite newer saves?")
                .setMessage(forcedFiles + " file(s) on this device are newer than the backup. "
                        + "Restoring will replace them.\n\nA copy of everything being replaced is "
                        + "saved first, as a pinned backup you can restore from.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Overwrite", (d, w) -> start())
                .show();
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

    private final class Adapter extends RecyclerView.Adapter<Holder> {

        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_plan, parent, false));
        }

        @Override public void onBindViewHolder(Holder h, int position) {
            RestorePlan p = plans.get(position);
            h.label.setText(p.targetLabel);

            int write = p.toWrite().size();
            h.count.setText(write == 0 ? "—" : String.valueOf(write));
            h.count.setTextColor(getResources().getColor(
                    write == 0 ? R.color.text_tertiary : R.color.accent, null));

            StringBuilder d = new StringBuilder();
            add(d, p.count(RestoreAction.CREATE), "new");
            add(d, p.count(RestoreAction.OVERWRITE_OLDER), "replaced");
            add(d, p.count(RestoreAction.SKIP_IDENTICAL), "already identical");
            add(d, p.count(RestoreAction.ORPHAN_ON_DEVICE), "on device only, left alone");
            if (d.length() == 0) d.append("nothing to do");
            h.detail.setText(d.toString());

            int conflicts = p.count(RestoreAction.CONFLICT_NEWER);
            int blocked = p.count(RestoreAction.BLOCKED_TIER);

            if (blocked > 0) {
                h.warning.setVisibility(View.VISIBLE);
                h.warning.setTextColor(getResources().getColor(R.color.text_tertiary, null));
                h.warning.setText(blocked + " file(s) are in app-private storage and need Shizuku.");
                h.itemView.setOnClickListener(null);
            } else if (conflicts > 0) {
                h.warning.setVisibility(View.VISIBLE);
                h.warning.setTextColor(getResources().getColor(
                        p.forced ? R.color.danger : R.color.warn, null));
                String age = ageOf(p.newestConflictMtime());
                h.warning.setText(p.forced
                        ? "WILL OVERWRITE " + conflicts + " newer file(s), the newest changed " + age
                          + ". Tap to leave them alone."
                        : conflicts + " file(s) on this device are newer, the newest changed " + age
                          + ". Skipped. Tap to overwrite them.");
                h.itemView.setOnClickListener(v -> {
                    plans.set(position, p.withForced(!p.forced));
                    notifyItemChanged(position);
                    updateSummary();
                });
            } else {
                h.warning.setVisibility(View.GONE);
                h.itemView.setOnClickListener(null);
            }
        }

        private void add(StringBuilder b, int n, String label) {
            if (n <= 0) return;
            if (b.length() > 0) b.append(" · ");
            b.append(n).append(' ').append(label);
        }

        @Override public int getItemCount() { return plans.size(); }
    }

    private static String ageOf(long mtimeMs) {
        if (mtimeMs <= 0) return "at an unknown time";
        long mins = Math.max(0, (System.currentTimeMillis() - mtimeMs) / 60_000);
        if (mins < 60) return mins + " minutes ago";
        long hours = mins / 60;
        if (hours < 48) return hours + " hours ago";
        return (hours / 24) + " days ago";
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView label, count, detail, warning;
        Holder(View v) {
            super(v);
            label = v.findViewById(R.id.label);
            count = v.findViewById(R.id.count);
            detail = v.findViewById(R.id.detail);
            warning = v.findViewById(R.id.warning);
        }
    }
}
