package com.tarikbc.emubackup;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every game, most recently played first, and each game's own history.
 *
 * <p>Two levels in one pane. The list: a console filter over rows that each say one thing
 * about the game's safety. The game page: what it is, where it lives, and a timeline of the
 * backups in which it changed. A on a moment asks, in plain words, whether to put that save
 * back; the restore is scoped to this game's files, and the safety copy scopes itself to
 * what is overwritten. DESIGN.md §6 list row, §12.
 */
final class GamesPane extends Pane {

    /** One line in the list. {@code entry} is null for a folder the app cannot see into. */
    private static final class Row {
        final GameHistory.Entry entry;
        final String targetId, badge, name, meta, status;
        final int statusColor;
        final long sortKey;

        Row(GameHistory.Entry entry, String targetId, String badge, String name, String meta,
            String status, int statusColor, long sortKey) {
            this.entry = entry;
            this.targetId = targetId;
            this.badge = badge;
            this.name = name;
            this.meta = meta;
            this.status = status;
            this.statusColor = statusColor;
            this.sortKey = sortKey;
        }
    }

    /** One backup that holds the open game. */
    private static final class Moment {
        final String versionId, note;
        final long atMs;
        final boolean safety;

        Moment(String versionId, long atMs, String note, boolean safety) {
            this.versionId = versionId;
            this.atMs = atMs;
            this.note = note;
            this.safety = safety;
        }
    }

    private HomeModel model;
    private ProfileAliases aliases;
    private FrameLayout root;
    private View listView, detailView;
    private LinearLayout chipBar;
    private RecyclerView list;
    private TextView subtitle, detailStatus, backupButton;
    private String filter;
    private final List<Row> all = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private Row open;
    private int openPosition = -1;

    GamesPane(ShellActivity host) {
        super(host);
    }

    // ---- list ----

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

        col.addView(Ui.bold(c, "Games", 24, R.color.text_primary));
        subtitle = Ui.text(c, model == null ? "Checking\u2026" : "", 14, R.color.text_secondary);
        col.addView(subtitle, top(4));

        HorizontalScrollView chips = new HorizontalScrollView(c);
        chips.setHorizontalScrollBarEnabled(false);
        chipBar = new LinearLayout(c);
        chipBar.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(chipBar);
        col.addView(chips, top(12));

        list = new RecyclerView(c);
        list.setLayoutManager(new LinearLayoutManager(c));
        list.setItemAnimator(null);
        list.setFocusedByDefault(true);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(c, 12), 0, gy);
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (model != null) {
            renderChips();
            renderList(-1);
        }
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
        aliases = new ProfileAliases(host);
        all.clear();
        all.addAll(rows(m));
        view();
        renderChips();
        int keep = focusedPosition();
        renderList(keep);
        if (open != null) {
            // The open game may have new history now. Find it again by key.
            for (Row r : all) {
                if (r.entry != null && open.entry != null && r.entry.key.equals(open.entry.key)) {
                    open = r;
                    break;
                }
            }
            refreshDetail();
        }
    }

    private List<Row> rows(HomeModel m) {
        List<Row> out = new ArrayList<>();
        if (m.scan == null || !m.scan.ok()) return out;
        TargetRegistry reg = m.scan.registry;
        long now = m.input.nowMs;
        for (GameHistory.Entry e : m.games.values()) {
            if (!m.selected.contains(e.targetId) || !reg.hasTarget(e.targetId)) continue;
            // A group with no bytes is a marker file, not a game.
            if (e.group.bytes == 0) continue;
            Target t = reg.target(e.targetId);
            Emulator em = reg.emulatorOf(e.targetId);
            String badge = Consoles.badge(em.id, t.id);
            StringBuilder meta = new StringBuilder(Sizes.human(e.group.bytes))
                    .append(" \u00b7 ").append(shortLabel(em));
            if (e.group.profileKey != null) {
                meta.append(" \u00b7 ").append(aliases.nameFor(e.group.profileKey));
            }
            String status;
            int hue;
            boolean afterBackup = e.newestMtimeMs() > e.lastBackedUpMs;
            if (!e.onDevice) {
                status = "not on this device";
                hue = R.color.text_tertiary;
            } else if (e.lastBackedUpMs == 0) {
                status = "not backed up yet";
                hue = R.color.warn;
            } else if (e.changedSinceBackup && afterBackup) {
                status = "changed since last backup";
                hue = R.color.warn;
            } else if (e.changedSinceBackup) {
                status = "partly backed up";
                hue = R.color.warn;
            } else {
                status = "backed up " + Ago.format(e.lastBackedUpMs, now);
                hue = R.color.text_secondary;
            }
            out.add(new Row(e, e.targetId, badge, displayName(e, t, m.names, badge), meta.toString(),
                    status, hue, Math.max(e.newestMtimeMs(), e.lastBackedUpMs)));
        }
        for (TargetScan ts : m.scan.scans) {
            if (ts.status != TargetStatus.TIER_UNAVAILABLE || !m.selected.contains(ts.targetId)) continue;
            Target t = reg.target(ts.targetId);
            Emulator em = reg.emulatorOf(ts.targetId);
            // A shared folder is locked only when storage access itself is missing.
            String need = t.tier == Tier.SHARED ? "needs storage access" : "needs extra access";
            out.add(new Row(null, ts.targetId, Consoles.badge(em.id, t.id), t.label,
                    shortLabel(em), need, R.color.text_tertiary, 0));
        }
        out.sort((a, b) -> {
            int c = Long.compare(b.sortKey, a.sortKey);
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    private static String shortLabel(Emulator em) {
        int cut = em.label.indexOf(" (");
        return cut > 0 ? em.label.substring(0, cut) : em.label;
    }

    private static String displayName(GameHistory.Entry e, Target t, GameNames names, String badge) {
        if (e.group.isWholeTarget()) return t.label;
        if (e.group.isUngrouped()) return "Other files in " + t.label;
        if (!names.isKnown(e.group.gameIdKind, e.group.gameKey)) {
            return Consoles.name(badge) + " title " + e.group.gameKey;
        }
        return names.lookup(e.group.gameIdKind, e.group.gameKey);
    }

    private void renderChips() {
        chipBar.removeAllViews();
        Set<String> present = new HashSet<>();
        for (Row r : all) present.add(r.badge);
        List<String> badges = new ArrayList<>(present);
        badges.sort((a, b) -> Integer.compare(Consoles.rank(a), Consoles.rank(b)));
        if (filter != null && !present.contains(filter)) filter = null;
        chipBar.addView(chip("All", null));
        for (String b : badges) chipBar.addView(chip(Consoles.name(b), b));
    }

    private View chip(String label, String badge) {
        boolean on = badge == null ? filter == null : badge.equals(filter);
        TextView t = Ui.bold(host, label, 14, on ? R.color.accent : R.color.text_secondary);
        t.setBackgroundResource(R.drawable.focus_ring);
        t.setPadding(Ui.dp(host, 16), Ui.dp(host, 8), Ui.dp(host, 16), Ui.dp(host, 8));
        t.setMinHeight(Ui.dp(host, 40));
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setTag(badge == null ? "" : badge);
        t.setOnClickListener(v -> {
            filter = badge;
            renderChips();
            renderList(-1);
            // The chips were rebuilt, so focus the one that now stands where this one was.
            View again = chipBar.findViewWithTag(badge == null ? "" : badge);
            if (again != null) again.requestFocus();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(host, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private int focusedPosition() {
        if (list == null) return -1;
        View f = list.getFocusedChild();
        return f == null ? -1 : list.getChildAdapterPosition(f);
    }

    private void renderList(int refocus) {
        shown.clear();
        for (Row r : all) if (filter == null || filter.equals(r.badge)) shown.add(r);
        int games = 0, locked = 0;
        for (Row r : shown) {
            if (r.entry != null) games++;
            else locked++;
        }
        StringBuilder s = new StringBuilder();
        s.append(games).append(games == 1 ? " game" : " games");
        if (locked > 0) s.append(" \u00b7 ").append(locked).append(locked == 1 ? " folder" : " folders")
                .append(" locked");
        if (filter != null) s.append(" \u00b7 ").append(Consoles.name(filter));
        if (model != null && model.storeError != null) {
            s.append(" \u00b7 ").append(model.input.where == Safety.Where.DRIVE
                    ? "Google Drive could not be reached" : "the backups could not be read")
                    .append(", so history is unknown");
        }
        subtitle.setText(s);
        list.setAdapter(new Adapter());
        if (refocus >= 0) focusRow(Math.min(refocus, shown.size() - 1));
    }

    private void focusRow(int pos) {
        if (pos < 0 || list == null) return;
        RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
        if (h != null) {
            h.itemView.requestFocus();
            return;
        }
        // Not laid out yet (a fresh adapter, or a pane that just came on screen). Rows exist
        // only after the next layout pass, and pre-draw is the first moment after it.
        list.scrollToPosition(pos);
        list.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        list.getViewTreeObserver().removeOnPreDrawListener(this);
                        RecyclerView.ViewHolder h2 = list.findViewHolderForAdapterPosition(pos);
                        if (h2 != null) h2.itemView.requestFocus();
                        return true;
                    }
                });
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            return new Holder(rowView());
        }

        @Override public void onBindViewHolder(Holder h, int position) {
            Row r = shown.get(position);
            h.badge.setText(r.badge);
            h.name.setText(r.name);
            h.meta.setText(r.meta);
            h.status.setText(r.status);
            h.status.setTextColor(Ui.color(host, r.statusColor));
            h.itemView.setOnClickListener(v -> openDetail(h.getBindingAdapterPosition()));
        }

        @Override public int getItemCount() {
            return shown.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView badge, name, meta, status;

        Holder(View v) {
            super(v);
            badge = (TextView) ((ViewGroup) v).getChildAt(0);
            LinearLayout mid = (LinearLayout) ((ViewGroup) v).getChildAt(1);
            name = (TextView) mid.getChildAt(0);
            meta = (TextView) mid.getChildAt(1);
            status = (TextView) ((ViewGroup) v).getChildAt(2);
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

        row.addView(badgeView(""));

        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(Ui.text(c, "", 17, R.color.text_primary));
        mid.addView(Ui.text(c, "", 13, R.color.text_secondary));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = Ui.dp(c, 14);
        row.addView(mid, mlp);

        TextView status = Ui.text(c, "", 14, R.color.text_secondary);
        status.setGravity(Gravity.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(c, 12);
        row.addView(status, slp);
        return row;
    }

    private TextView badgeView(String text) {
        TextView b = Ui.bold(host, text, 12, R.color.text_secondary);
        b.setBackground(Ui.card(host, R.color.surface_high));
        b.setPadding(Ui.dp(host, 8), Ui.dp(host, 3), Ui.dp(host, 8), Ui.dp(host, 3));
        b.setMinWidth(Ui.dp(host, 44));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    // ---- game page ----

    private void openDetail(int position) {
        if (position < 0 || position >= shown.size()) return;
        Row r = shown.get(position);
        if (r.entry == null) {
            host.open(PermissionActivity.class);
            return;
        }
        open = r;
        openPosition = position;
        detailView = buildDetail(r);
        root.removeView(listView);
        root.addView(detailView);
        host.refreshLegend();
        host.focusByDefault(backupButton);
    }

    private void closeDetail() {
        if (detailView != null) root.removeView(detailView);
        detailView = null;
        open = null;
        if (listView.getParent() == null) root.addView(listView);
        host.refreshLegend();
        focusRow(openPosition);
    }

    private void refreshDetail() {
        if (open == null || root == null) return;
        View old = detailView;
        detailView = buildDetail(open);
        if (old != null) root.removeView(old);
        root.addView(detailView);
    }

    private View buildDetail(Row r) {
        ShellActivity c = host;
        GameHistory.Entry e = r.entry;
        boolean tall = c.isTall();
        TargetRegistry reg = model.scan.registry;
        Target t = reg.target(r.targetId);
        Emulator em = reg.emulatorOf(r.targetId);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int gx = Ui.dp(c, tall ? 20 : 32), gy = Ui.dp(c, tall ? 16 : 20);
        col.setPadding(gx, gy, gx, 0);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(badgeView(r.badge));
        TextView name = Ui.bold(c, r.name, 24, R.color.text_primary);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.dp(c, 12);
        head.addView(name, nlp);
        col.addView(head);

        StringBuilder meta = new StringBuilder(shortLabel(em)).append(" \u00b7 ")
                .append(Sizes.human(e.group.bytes)).append(" \u00b7 kept in ").append(t.label);
        if (e.group.profileKey != null) {
            meta.append(" \u00b7 profile ").append(aliases.nameFor(e.group.profileKey));
        }
        col.addView(Ui.text(c, meta.toString(), 14, R.color.text_secondary), top(6));
        col.addView(Ui.text(c, r.status, 14, r.statusColor), top(2));

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(tall ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        backupButton = Ui.primaryButton(c, "Back up now");
        backupButton.setFocusedByDefault(true);
        backupButton.setOnClickListener(v -> host.open(BackupActivity.class));
        buttons.addView(backupButton, buttonLp(tall, false));
        if (e.group.profileKey != null) {
            TextView rename = Ui.secondaryButton(c, "Rename profile");
            rename.setOnClickListener(v -> rename());
            buttons.addView(rename, buttonLp(tall, true));
        }
        col.addView(buttons, top(20));
        detailStatus = Ui.text(c, "", 14, R.color.text_secondary);
        col.addView(detailStatus, top(8));

        List<Moment> moments = moments(e);
        col.addView(Ui.caption(c, "Put back an older save"), top(18));
        if (moments.isEmpty()) {
            col.addView(Ui.text(c, e.onDevice
                    ? "No backup holds this game yet. Back up now and it will be here."
                    : "No backup holds this game.", 15, R.color.text_secondary), top(8));
        } else {
            RecyclerView timeline = new RecyclerView(c);
            timeline.setLayoutManager(new LinearLayoutManager(c));
            timeline.setItemAnimator(null);
            timeline.setClipToPadding(false);
            timeline.setPadding(0, Ui.dp(c, 8), 0, gy);
            timeline.setAdapter(new MomentAdapter(moments, r));
            col.addView(timeline, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return col;
    }

    private LinearLayout.LayoutParams buttonLp(boolean tall, boolean second) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                tall ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (second) {
            if (tall) lp.topMargin = Ui.dp(host, 10);
            else lp.leftMargin = Ui.dp(host, 12);
        }
        return lp;
    }

    private List<Moment> moments(GameHistory.Entry e) {
        List<Moment> out = new ArrayList<>();
        for (GameHistory.Snapshot s : e.snapshots) {
            String note = s.first ? "First backup"
                    : s.filesChanged + (s.filesChanged == 1 ? " file changed" : " files changed");
            out.add(new Moment(s.versionId, s.atMs, note, false));
        }
        for (String vid : e.safetyCopies) {
            long at = 0;
            for (IndexEntry ie : model.index) if (ie.id.equals(vid)) at = ie.createdAtMs;
            out.add(new Moment(vid, at, "Safety copy, made before a restore", true));
        }
        out.sort((a, b) -> Long.compare(b.atMs, a.atMs));
        return out;
    }

    private final class MomentAdapter extends RecyclerView.Adapter<MomentHolder> {
        private final List<Moment> items;
        private final Row row;

        MomentAdapter(List<Moment> items, Row row) {
            this.items = items;
            this.row = row;
        }

        @Override public MomentHolder onCreateViewHolder(ViewGroup parent, int type) {
            ShellActivity c = host;
            LinearLayout v = new LinearLayout(c);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setBackgroundResource(R.drawable.focus_ring);
            v.setPadding(Ui.dp(c, 18), Ui.dp(c, 12), Ui.dp(c, 18), Ui.dp(c, 12));
            v.setMinimumHeight(Ui.dp(c, 60));
            v.setFocusable(true);
            v.setClickable(true);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(c, 10);
            v.setLayoutParams(lp);
            v.addView(Ui.text(c, "", 17, R.color.text_primary));
            v.addView(Ui.text(c, "", 14, R.color.text_secondary));
            return new MomentHolder(v);
        }

        @Override public void onBindViewHolder(MomentHolder h, int position) {
            Moment m = items.get(position);
            h.when.setText(When.format(m.atMs, model.input.nowMs));
            h.note.setText(m.note);
            h.itemView.setOnClickListener(v -> confirmRestore(row, m));
        }

        @Override public int getItemCount() {
            return items.size();
        }
    }

    private static final class MomentHolder extends RecyclerView.ViewHolder {
        final TextView when, note;

        MomentHolder(LinearLayout v) {
            super(v);
            when = (TextView) v.getChildAt(0);
            note = (TextView) v.getChildAt(1);
        }
    }

    // ---- put back ----

    private void confirmRestore(Row row, Moment m) {
        GameHistory.Entry e = row.entry;
        String when = When.format(m.atMs, model.input.nowMs);
        detailStatus.setText("Comparing the backup from " + when + " with what is on the device\u2026");
        TargetRegistry reg = model.scan.registry;
        host.io().execute(() -> {
            RestoreSession session = null;
            String failure = null;
            try {
                BackupSink store = Stores.active(host);
                Manifest manifest = new ManifestCache(host).get(store, m.versionId);
                SaveGroup stored = GameHistory.groupsOf(reg, manifest).get(e.key);
                // The filter is the game's files then and now, so a file added since shows
                // up as left alone rather than vanishing from the preview.
                Set<String> paths = new HashSet<>();
                if (stored != null) for (FileStat f : stored.files) paths.add(f.path);
                if (e.onDevice) for (FileStat f : e.group.files) paths.add(f.path);
                session = RestoreSession.load(host, m.versionId,
                        Collections.singletonMap(e.targetId, paths));
            } catch (Exception ex) {
                failure = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            final RestoreSession s = session;
            final String err = failure;
            host.ui().post(() -> {
                if (host.isFinishing() || host.isDestroyed() || open != row) return;
                detailStatus.setText("");
                showRestoreSheet(row, m, when, s, err);
            });
        });
    }

    private void showRestoreSheet(Row row, Moment m, String when, RestoreSession s, String err) {
        GameHistory.Entry e = row.entry;
        Emulator em = model.scan.registry.emulatorOf(e.targetId);
        if (s == null || !s.ok()) {
            showSheet("Could not read that backup", err != null ? err : s.error, null, "OK", null);
            return;
        }
        RestorePlan plan = s.plans.isEmpty() ? null : s.plans.get(0);
        if (plan == null || plan.items.isEmpty()) {
            showSheet("Nothing to put back", "That backup does not hold this game.", null, "OK", null);
            return;
        }
        if (plan.count(RestoreAction.BLOCKED_TIER) > 0) {
            showSheet("This save needs extra access",
                    "It lives in a folder only " + shortLabel(em) + " can see. Set up extra "
                            + "access first, then come back here.",
                    "Cancel", "Set up extra access", () -> host.open(PermissionActivity.class));
            return;
        }
        int newer = plan.count(RestoreAction.CONFLICT_NEWER);
        int suspect = plan.count(RestoreAction.SIZE_CONFLICT);
        boolean force = newer > 0 || suspect > 0;
        RestorePlan run = force ? plan.withForced(true) : plan;
        if (run.toWrite().isEmpty()) {
            showSheet("Already there",
                    "Your current save already matches the backup from " + when + ".",
                    null, "OK", null);
            return;
        }

        StringBuilder body = new StringBuilder();
        String primary;
        if (newer > 0) {
            body.append("Your current save is newer, from ")
                    .append(When.format(plan.newestConflictMtime(), model.input.nowMs))
                    .append(". Putting back the older one replaces it. A safety copy is made "
                            + "first, so this can be undone.");
            primary = "Replace it anyway";
        } else {
            body.append("Your current save is kept as a safety copy first, so this can be "
                    + "undone. Files you have added since then are left in place.");
            primary = "Put it back";
        }
        if (suspect > 0) {
            body.append("\n\n").append(suspect).append(suspect == 1
                    ? " file has the same date but different content; it is replaced too."
                    : " files have the same date but different content; they are replaced too.");
        }
        if (!e.onDevice && e.group.profileKey != null) {
            body.append("\n\nThis profile is not on the device any more. ").append(shortLabel(em))
                    .append(" may not show the save until the profile exists again.");
        }
        body.append("\n\n").append(shortLabel(em)).append(" may need relaunching to notice.");

        showSheet("Put back " + row.name + " from " + when + "?", body.toString(),
                "Cancel", primary, () -> host.startRestore(
                        new BackupService.RestoreRequest(m.versionId, Collections.singletonList(run))));
    }

    // ---- profile rename ----

    private void rename() {
        if (open == null || open.entry == null || open.entry.group.profileKey == null) return;
        String uuid = open.entry.group.profileKey;
        showInputSheet("Rename this profile", "Only EmuBackup uses this name.",
                aliases.nameFor(uuid), "Cancel", "Save", name -> {
                    if (name.isEmpty()) return;
                    aliases.set(uuid, name);
                    onModel(model);
                });
    }

    // ---- pane contract ----

    @Override String[] legend() {
        if (open == null) return new String[] { "A", "Open", "B", "Back" };
        if (open.entry != null && open.entry.group.profileKey != null) {
            return new String[] { "A", "Select", "B", "Back", "X", "Rename profile" };
        }
        return new String[] { "A", "Select", "B", "Back" };
    }

    @Override void onX() {
        rename();
    }

    @Override View defaultFocus() {
        view();
        if (open != null) return backupButton;
        if (list != null) {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(0);
            if (h != null) return h.itemView;
        }
        if (!shown.isEmpty()) {
            focusRow(0);
            // The RecyclerView holds focus without a ring until its first row exists.
            return list;
        }
        return chipBar != null && chipBar.getChildCount() > 0 ? chipBar.getChildAt(0) : null;
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
