package com.tarikbc.emubackup;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every backup by date, newest first, and one backup's page.
 *
 * <p>The list answers "what do I have and is it any good": when, how big, where, and whether
 * it was checked. Unreachable is its own state with a retry, never rendered as empty. The page
 * offers the three things one can do with a whole backup: put all of it back, check it, or
 * export it as a single file. DESIGN.md §6, §8.
 */
final class BackupsPane extends Pane {

    private HomeModel model;
    private FrameLayout root;
    private View listView, detailView;
    private RecyclerView list;
    private TextView subtitle, empty, retry, primary;
    private final List<IndexEntry> shown = new ArrayList<>();
    private IndexEntry open;
    private int openPosition = -1;

    BackupsPane(ShellActivity host) {
        super(host);
    }

    @Override protected View create() {
        root = new FrameLayout(host);
        listView = buildList();
        root.addView(listView);
        if (open != null) {
            detailView = buildDetail(open);
            root.removeView(listView);
            root.addView(detailView);
        }
        return root;
    }

    private View buildList() {
        ShellActivity c = host;
        boolean tall = c.isTall();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int gx = Ui.dp(c, tall ? 20 : 32), gy = Ui.dp(c, tall ? 16 : 20);
        col.setPadding(gx, gy, gx, 0);

        col.addView(Ui.bold(c, "Backups", 24, R.color.text_primary));
        subtitle = Ui.text(c, model == null ? "Checking\u2026" : "", 14, R.color.text_secondary);
        col.addView(subtitle, top(4));

        empty = Ui.text(c, "", 15, R.color.text_secondary);
        empty.setVisibility(View.GONE);
        col.addView(empty, top(16));
        retry = Ui.secondaryButton(c, "Try again");
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> host.reload());
        LinearLayout.LayoutParams rlp = top(16);
        rlp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        col.addView(retry, rlp);

        list = new RecyclerView(c);
        list.setLayoutManager(new LinearLayoutManager(c));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(c, 16), 0, gy);
        list.setFocusedByDefault(true);
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (model != null) renderList(-1);
        return col;
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(host, dp);
        return lp;
    }

    @Override void refresh() {
        if (model == null && host.model() != null) onModel(host.model());
    }

    @Override void onModel(HomeModel m) {
        model = m;
        view();
        int keep = focusedPosition();
        renderList(keep);
        if (open != null) {
            for (IndexEntry e : m.index) if (e.id.equals(open.id)) open = e;
            refreshDetail();
        }
    }

    private int focusedPosition() {
        if (list == null) return -1;
        View f = list.getFocusedChild();
        return f == null ? -1 : list.getChildAdapterPosition(f);
    }

    private String where() {
        return model.input.where == Safety.Where.DRIVE ? "Google Drive"
                : model.input.where == Safety.Where.FOLDER ? model.whereName() : "this device";
    }

    private void renderList(int refocus) {
        shown.clear();
        shown.addAll(model.index);
        Collections.reverse(shown);

        boolean unreachable = model.storeError != null;
        retry.setVisibility(unreachable ? View.VISIBLE : View.GONE);
        if (unreachable) {
            subtitle.setText(Character.toUpperCase(where().charAt(0)) + where().substring(1)
                    + " could not be reached.");
            empty.setText(model.storeError);
            empty.setVisibility(View.VISIBLE);
            retry.setFocusedByDefault(true);
        } else if (shown.isEmpty()) {
            subtitle.setText("Nothing here yet \u00b7 " + where());
            empty.setText("No backup has been made yet. Back up now from Home and it will be here.");
            empty.setVisibility(View.VISIBLE);
        } else {
            long total = 0;
            int copies = 0;
            for (IndexEntry e : shown) {
                total += e.bytes;
                if (e.isPreRestore()) copies++;
            }
            StringBuilder s = new StringBuilder();
            s.append(shown.size() - copies).append(shown.size() - copies == 1 ? " backup" : " backups");
            if (copies > 0) s.append(", ").append(copies).append(copies == 1 ? " safety copy" : " safety copies");
            s.append(" \u00b7 ").append(Sizes.human(total)).append(" \u00b7 ").append(where());
            subtitle.setText(s);
            empty.setVisibility(View.GONE);
        }
        list.setAdapter(new Adapter());
        if (refocus >= 0) focusRow(Math.min(refocus, shown.size() - 1));
    }

    private void focusRow(int pos) {
        Ui.focusRow(list, pos, host.keyDriven());
    }

    /** One line about whether and when this backup was checked. */
    private String checkedLine(IndexEntry e, long now) {
        VerifyMemory.Entry v = VerifyMemory.get(host, e.id);
        if (v == null) return "not checked yet";
        return (v.ok ? "\u2713 checked " : "\u2717 problems found ") + Ago.format(v.atMs, now);
    }

    private int checkedHue(IndexEntry e) {
        VerifyMemory.Entry v = VerifyMemory.get(host, e.id);
        if (v == null) return R.color.text_tertiary;
        return v.ok ? R.color.ok : R.color.danger;
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            return new Holder(rowView());
        }

        @Override public void onBindViewHolder(Holder h, int position) {
            IndexEntry e = shown.get(position);
            long now = model.input.nowMs;
            h.when.setText(When.format(e.createdAtMs, now));
            String meta = Sizes.human(e.bytes) + " \u00b7 " + where();
            if (e.isPreRestore()) meta += " \u00b7 safety copy, made before a restore, kept";
            else if (e.pinned) meta += " \u00b7 kept";
            h.meta.setText(meta);
            h.status.setText(checkedLine(e, now));
            h.status.setTextColor(Ui.color(host, checkedHue(e)));
            h.itemView.setOnClickListener(v -> openDetail(h.getBindingAdapterPosition()));
        }

        @Override public int getItemCount() {
            return shown.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView when, meta, status;

        Holder(View v) {
            super(v);
            LinearLayout mid = (LinearLayout) ((ViewGroup) v).getChildAt(0);
            when = (TextView) mid.getChildAt(0);
            meta = (TextView) mid.getChildAt(1);
            status = (TextView) ((ViewGroup) v).getChildAt(1);
        }
    }

    private View rowView() {
        ShellActivity c = host;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.focus_ring);
        row.setPadding(Ui.dp(c, 18), Ui.dp(c, 12), Ui.dp(c, 18), Ui.dp(c, 12));
        row.setMinimumHeight(Ui.dp(c, 60));
        row.setFocusable(true);
        row.setClickable(true);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 10);
        row.setLayoutParams(lp);
        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(Ui.text(c, "", 17, R.color.text_primary));
        mid.addView(Ui.text(c, "", 13, R.color.text_secondary));
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView status = Ui.text(c, "", 14, R.color.text_secondary);
        status.setGravity(Gravity.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(c, 12);
        row.addView(status, slp);
        return row;
    }

    // ---- backup page ----

    private void openDetail(int position) {
        if (position < 0 || position >= shown.size()) return;
        open = shown.get(position);
        openPosition = position;
        detailView = buildDetail(open);
        root.removeView(listView);
        root.addView(detailView);
        host.refreshLegend();
        host.focusByDefault(primary);
    }

    private void closeDetail() {
        if (detailView != null) root.removeView(detailView);
        detailView = null;
        open = null;
        if (listView.getParent() == null) root.addView(listView);
        host.refreshLegend();
        Ui.focusRow(list, openPosition, true);
    }

    private void refreshDetail() {
        if (open == null || root == null) return;
        View old = detailView;
        detailView = buildDetail(open);
        if (old != null) root.removeView(old);
        root.addView(detailView);
    }

    private View buildDetail(IndexEntry e) {
        ShellActivity c = host;
        boolean tall = c.isTall();
        long now = model.input.nowMs;
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int gx = Ui.dp(c, tall ? 20 : 32), gy = Ui.dp(c, tall ? 16 : 20);
        col.setPadding(gx, gy, gx, gy);
        sv.addView(col);

        col.addView(Ui.bold(c, (e.isPreRestore() ? "Safety copy from " : "Backup from ")
                + When.format(e.createdAtMs, now), 24, R.color.text_primary));
        TextView meta = Ui.text(c, Sizes.human(e.bytes) + " \u00b7 " + where() + " \u00b7 counting games\u2026",
                14, R.color.text_secondary);
        col.addView(meta, top(6));
        if (e.isPreRestore()) {
            col.addView(Ui.text(c, "Made automatically before a restore, so that restore can be "
                    + "undone. Kept until you remove it.", 14, R.color.text_secondary), top(2));
        }
        TextView checked = Ui.text(c, checkedLine(e, now), 14, checkedHue(e));
        col.addView(checked, top(2));

        primary = Ui.primaryButton(c, "Put back everything from this backup\u2026");
        primary.setFocusedByDefault(true);
        primary.setOnClickListener(v -> {
            Intent i = new Intent(host, RestorePreviewActivity.class);
            i.putExtra(RestorePreviewActivity.EXTRA_VERSION, e.id);
            host.startActivity(i);
        });
        LinearLayout.LayoutParams plp = top(20);
        plp.width = tall ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        col.addView(primary, plp);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(tall ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        TextView check = Ui.secondaryButton(c, "Check it is intact");
        check.setOnClickListener(v -> {
            Intent i = new Intent(host, VerifyActivity.class);
            i.putExtra(VerifyActivity.EXTRA_VERSION, e.id);
            host.startActivity(i);
        });
        TextView export = Ui.secondaryButton(c, "Export as one file");
        export.setOnClickListener(v -> {
            Intent i = new Intent(host, ExportActivity.class);
            i.putExtra(ExportActivity.EXTRA_VERSION, e.id);
            host.startActivity(i);
        });
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(
                tall ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(
                tall ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (tall) b.topMargin = Ui.dp(c, 10);
        else b.leftMargin = Ui.dp(c, 12);
        row.addView(check, a);
        row.addView(export, b);
        col.addView(row, top(12));

        col.addView(Ui.text(c, "Putting back a single game is done from Games, where each "
                + "game has its own history.", 14, R.color.text_tertiary), top(20));

        countGames(e, meta);
        return sv;
    }

    /** The manifest says how many games and folders a backup holds; it is fetched once, cached. */
    private void countGames(IndexEntry e, TextView meta) {
        if (model.scan == null || !model.scan.ok()) return;
        TargetRegistry reg = model.scan.registry;
        host.io().execute(() -> {
            String line;
            try {
                ManifestCache cache = new ManifestCache(host);
                BackupSink store = cache.has(e.id) ? null : Stores.active(host);
                Manifest m = cache.get(store, e.id);
                int folders = 0;
                for (ManifestTarget t : m.targets) if (!t.files.isEmpty()) folders++;
                int games = 0;
                for (SaveGroup g : GameHistory.groupsOf(reg, m).values()) if (g.bytes > 0) games++;
                line = games + (games == 1 ? " game in " : " games in ") + folders
                        + (folders == 1 ? " save folder" : " save folders");
            } catch (Exception ex) {
                line = "contents could not be read";
            }
            final String l = line;
            host.ui().post(() -> {
                if (host.isFinishing() || host.isDestroyed() || open != e) return;
                meta.setText(Sizes.human(e.bytes) + " \u00b7 " + where() + " \u00b7 " + l);
            });
        });
    }

    // ---- pane contract ----

    @Override String[] legend() {
        return open == null ? new String[] { "A", "Open", "B", "Back" }
                : new String[] { "A", "Select", "B", "Back" };
    }

    @Override View defaultFocus() {
        view();
        if (open != null) return primary;
        if (retry != null && retry.getVisibility() == View.VISIBLE) return retry;
        if (list != null) {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(0);
            if (h != null) return h.itemView;
        }
        if (!shown.isEmpty()) {
            focusRow(0);
            return list;
        }
        return null;
    }

    @Override boolean back() {
        if (super.back()) return true;
        if (open != null) {
            closeDetail();
            return true;
        }
        return false;
    }
}
