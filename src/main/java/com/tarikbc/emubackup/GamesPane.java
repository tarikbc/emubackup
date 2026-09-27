package com.tarikbc.emubackup;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every game, most recently played first, and each game's own history.
 *
 * <p>Two levels in one pane. The list: a search, a console filter, a row of refinements
 * (needs you, one chip per profile), and rows that each say one thing about the game's
 * safety. A game saved under several profiles is one row; the profiles are chips on its page.
 * The game page: what it is, where it lives, and a timeline of the backups in which it
 * changed, each saying what changed and what it holds. A on a moment asks, in plain words,
 * exactly which files would be written, replaced or left alone; the restore is scoped to this
 * game's files on this profile, and the safety copy scopes itself to what is overwritten.
 * DESIGN.md §6 list row, §12.
 */
final class GamesPane extends Pane {

    /** One line in the list. {@code entries} is empty for a folder the app cannot see into. */
    private static final class Row {
        /** The same game under each profile it is saved for, most recently played first. */
        final List<GameHistory.Entry> entries;
        /** Which of {@link #entries} the page shows. */
        int selected;
        final String targetId, badge, name, meta, status;
        final int statusColor;
        final long sortKey;
        final boolean needsYou;
        /** Profile names in the order of {@link #entries}; empty when the game has no profile axis. */
        final List<String> profiles;
        final File icon, hero;

        Row(List<GameHistory.Entry> entries, String targetId, String badge, String name, String meta,
            String status, int statusColor, long sortKey, boolean needsYou, List<String> profiles,
            File icon, File hero) {
            this.entries = entries;
            this.targetId = targetId;
            this.badge = badge;
            this.name = name;
            this.meta = meta;
            this.status = status;
            this.statusColor = statusColor;
            this.sortKey = sortKey;
            this.needsYou = needsYou;
            this.profiles = profiles;
            this.icon = icon;
            this.hero = hero;
        }

        GameHistory.Entry entry() {
            return entries.isEmpty() ? null : entries.get(selected);
        }

        /** The identity the open page follows across reloads. */
        String key() {
            return entries.isEmpty() ? targetId : entries.get(0).key;
        }
    }

    /** One line about a game's safety, with a rank so a merged row can show the worst. */
    private static final class Status {
        final String text;
        final int hue, rank;

        Status(String text, int hue, int rank) {
            this.text = text;
            this.hue = hue;
            this.rank = rank;
        }
    }

    /** One backup that holds the open game. */
    private static final class Moment {
        final String versionId;
        final CharSequence note;
        /** What the backup holds: "52 files · 15 MB". */
        final String holds;
        final long atMs;
        final boolean safety;

        Moment(String versionId, CharSequence note, String holds, long atMs, boolean safety) {
            this.versionId = versionId;
            this.note = note;
            this.holds = holds;
            this.atMs = atMs;
            this.safety = safety;
        }
    }

    private HomeModel model;
    private ProfileAliases aliases;
    private final ArtLoader art;
    private FrameLayout root;
    private View listView, detailView;
    private LinearLayout chipBar, refineBar;
    private HorizontalScrollView refineScroll;
    private RecyclerView list;
    private TextView subtitle, detailStatus, backupButton;
    private String filter;
    private String loadingNote;
    private String query = "";
    private boolean needsYou;
    private String profile;
    private final List<Row> all = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private Row open;
    private int openPosition = -1;

    GamesPane(ShellActivity host) {
        super(host);
        art = new ArtLoader(host, host.ui());
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
        subtitle = Ui.text(c, model == null ? "Checking…" : "", 14, R.color.text_secondary);
        col.addView(subtitle, top(4));

        HorizontalScrollView chips = new HorizontalScrollView(c);
        chips.setHorizontalScrollBarEnabled(false);
        chipBar = new LinearLayout(c);
        chipBar.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(chipBar);
        col.addView(chips, top(12));

        refineScroll = new HorizontalScrollView(c);
        refineScroll.setHorizontalScrollBarEnabled(false);
        refineBar = new LinearLayout(c);
        refineBar.setOrientation(LinearLayout.HORIZONTAL);
        refineScroll.addView(refineBar);
        refineScroll.setVisibility(View.GONE);
        col.addView(refineScroll, top(8));

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

    @Override void onLoading(String note) {
        loadingNote = note;
        if (model != null && model.historyPending && subtitle != null) renderSubtitle();
    }

    @Override void onModel(HomeModel m) {
        model = m;
        loadingNote = null;
        aliases = new ProfileAliases(host);
        all.clear();
        all.addAll(rows(m));
        view();
        renderChips();
        int keep = focusedPosition();
        renderList(keep);
        if (open != null) {
            // The open game may have new history now. Find it again by key, on the same profile.
            String wanted = open.entry() == null ? null : open.entry().group.profileKey;
            for (Row r : all) {
                if (!r.entries.isEmpty() && r.key().equals(open.key())) {
                    for (int i = 0; i < r.entries.size(); i++) {
                        String pk = r.entries.get(i).group.profileKey;
                        if (pk == null ? wanted == null : pk.equals(wanted)) r.selected = i;
                    }
                    open = r;
                    break;
                }
            }
            refreshDetail();
        }
    }

    private Status statusOf(GameHistory.Entry e, HomeModel m) {
        long now = m.input.nowMs;
        if (m.historyPending) return new Status("checking…", R.color.text_tertiary, 0);
        // The store could not be read and nothing was remembered: not "never backed up", unknown.
        if (m.storeError != null && m.index.isEmpty() && e.onDevice) {
            return new Status("history unknown", R.color.text_tertiary, 0);
        }
        boolean afterBackup = e.newestMtimeMs() > e.lastBackedUpMs;
        if (!e.onDevice) return new Status("not on this device", R.color.text_tertiary, 0);
        if (e.lastBackedUpMs == 0) return new Status("not backed up yet", R.color.warn, 3);
        if (GameLabels.looksResetNow(e, m.index)) return new Status("looks like it started over", R.color.warn, 4);
        if (e.changedSinceBackup && afterBackup) return new Status("changed since last backup", R.color.warn, 2);
        if (e.changedSinceBackup) {
            boolean aside = false;
            for (HomeModel.Problem pr : m.problems) if (pr.targetId.equals(e.targetId) && pr.setAside) aside = true;
            return aside ? new Status("partly backed up · set aside", R.color.text_tertiary, 0)
                    : new Status("partly backed up", R.color.warn, 1);
        }
        return new Status("backed up " + Ago.format(e.lastBackedUpMs, now), R.color.text_secondary, 0);
    }

    private List<Row> rows(HomeModel m) {
        List<Row> out = new ArrayList<>();
        if (m.scan == null || !m.scan.ok()) return out;
        TargetRegistry reg = m.scan.registry;

        // One row per game. A game saved under several profiles is one row with every profile
        // behind it; the page shows them as chips. Grouped by target and game, never by profile.
        Map<String, List<GameHistory.Entry>> merged = new LinkedHashMap<>();
        for (GameHistory.Entry e : m.games.values()) {
            if (!m.selected.contains(e.targetId) || !reg.hasTarget(e.targetId)) continue;
            // A group with no bytes is a marker file, not a game.
            if (e.group.bytes == 0) continue;
            boolean perProfile = e.group.profileKey != null && e.group.gameKey != null
                    && !e.group.isUngrouped() && !e.group.isWholeTarget();
            String key = perProfile ? e.targetId + "|" + e.group.gameKey : e.key;
            merged.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        for (List<GameHistory.Entry> entries : merged.values()) {
            entries.sort((a, b) -> Long.compare(GameLabels.recencyMs(b), GameLabels.recencyMs(a)));
            GameHistory.Entry first = entries.get(0);
            Target t = reg.target(first.targetId);
            Emulator em = reg.emulatorOf(first.targetId);
            String badge = Consoles.badge(em.id, t.id);
            long bytes = 0, sortKey = 0;
            Status worst = null;
            List<String> profiles = new ArrayList<>();
            for (GameHistory.Entry e : entries) {
                bytes += e.group.bytes;
                sortKey = Math.max(sortKey, GameLabels.recencyMs(e));
                Status s = statusOf(e, m);
                if (worst == null || s.rank > worst.rank) worst = s;
                if (e.group.profileKey != null) profiles.add(profileName(e.group.profileKey));
            }
            StringBuilder meta = new StringBuilder(Sizes.human(bytes)).append(" · ").append(shortLabel(em));
            if (!profiles.isEmpty()) meta.append(" · ").append(String.join(", ", profiles));
            out.add(new Row(entries, first.targetId, badge, displayName(first, t, m.names, badge),
                    meta.toString(), worst.text, worst.hue, sortKey, worst.hue == R.color.warn, profiles,
                    artIcon(m, first, t), artHero(m, first, t)));
        }
        for (TargetScan ts : m.scan.scans) {
            if (ts.status != TargetStatus.TIER_UNAVAILABLE || !m.selected.contains(ts.targetId)) continue;
            Target t = reg.target(ts.targetId);
            Emulator em = reg.emulatorOf(ts.targetId);
            // A shared folder is locked only when storage access itself is missing.
            String need = t.tier == Tier.SHARED ? "needs storage access" : "needs extra access";
            out.add(new Row(new ArrayList<>(), ts.targetId, Consoles.badge(em.id, t.id), t.label,
                    shortLabel(em), need, R.color.text_tertiary, 0, false, new ArrayList<>(), null, null));
        }
        out.sort((a, b) -> {
            int c = Long.compare(b.sortKey, a.sortKey);
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    /** The id Cocoon's art is keyed by for this game, the same way its name is looked up. */
    private static IdKind artKind(GameHistory.Entry e, Target t) {
        return "dolphin-wii".equals(t.id) ? IdKind.GC_GAME_ID : e.group.gameIdKind;
    }

    private static String artId(GameHistory.Entry e, Target t) {
        if (e.group.gameKey == null || e.group.isUngrouped()) return null;
        return "dolphin-wii".equals(t.id) ? TitleIds.wiiNandGameId(e.group.gameKey) : e.group.gameKey;
    }

    private static File artIcon(HomeModel m, GameHistory.Entry e, Target t) {
        return m.art.icon(artKind(e, t), artId(e, t));
    }

    private static File artHero(HomeModel m, GameHistory.Entry e, Target t) {
        return m.art.hero(artKind(e, t), artId(e, t));
    }

    /** The person's own name for a profile, else what the emulator calls it, else its id. */
    private String profileName(String uuid) {
        if (aliases.hasAlias(uuid)) return aliases.nameFor(uuid);
        String eden = model == null ? null : model.profileNames.get(uuid.toUpperCase(java.util.Locale.ROOT));
        return eden != null ? eden : aliases.nameFor(uuid);
    }

    private static String shortLabel(Emulator em) {
        int cut = em.label.indexOf(" (");
        return cut > 0 ? em.label.substring(0, cut) : em.label;
    }

    private static String displayName(GameHistory.Entry e, Target t, GameNames names, String badge) {
        return GameLabels.displayName(e, t, names, badge);
    }

    // ---- chips ----

    private void renderChips() {
        chipBar.removeAllViews();
        Set<String> present = new HashSet<>();
        for (Row r : all) present.add(r.badge);
        List<String> badges = new ArrayList<>(present);
        badges.sort((a, b) -> Integer.compare(Consoles.rank(a), Consoles.rank(b)));
        if (filter != null && !present.contains(filter)) filter = null;
        chipBar.addView(searchChip());
        chipBar.addView(chip("All", null));
        for (String b : badges) chipBar.addView(chip(Consoles.name(b), b));
        renderRefine();
    }

    /** The one chip that is not a filter value: it opens the search sheet, and shows the query. */
    private View searchChip() {
        boolean on = !query.isEmpty();
        TextView t = Ui.bold(host, on ? "“" + query + "” ×" : "Search",
                14, on ? R.color.accent : R.color.text_secondary);
        Ui.iconStart(t, R.drawable.ic_search, on ? R.color.accent : R.color.text_tertiary, 16, 6);
        styleChip(t, "search");
        t.setOnClickListener(v -> openSearch());
        return t;
    }

    private View chip(String label, String badge) {
        boolean on = badge == null ? filter == null : badge.equals(filter);
        TextView t = Ui.bold(host, label, 14, on ? R.color.accent : R.color.text_secondary);
        Ui.iconStart(t, familyIcon(badge), on ? R.color.accent : R.color.text_tertiary, 16, 6);
        styleChip(t, badge == null ? "" : badge);
        t.setOnClickListener(v -> {
            filter = badge;
            renderChips();
            renderList(-1);
            // The chips were rebuilt, so focus the one that now stands where this one was.
            View again = chipBar.findViewWithTag(badge == null ? "" : badge);
            if (again != null) again.requestFocus();
        });
        return t;
    }

    private void styleChip(TextView t, String tag) {
        t.setBackgroundResource(R.drawable.focus_ring);
        t.setPadding(Ui.dp(host, 16), Ui.dp(host, 8), Ui.dp(host, 16), Ui.dp(host, 8));
        t.setMinHeight(Ui.dp(host, 40));
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setTag(tag);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(host, 8);
        t.setLayoutParams(lp);
    }

    /**
     * The second row: toggles that cut across consoles. "Needs you" when any row in the
     * console needs something; one chip per profile when the rows carry profiles. Hidden when
     * neither applies, so a device without profiles never sees an empty strip.
     */
    private void renderRefine() {
        refineBar.removeAllViews();
        boolean anyNeed = false;
        Set<String> profiles = new LinkedHashSet<>();
        for (Row r : all) {
            if (filter != null && !filter.equals(r.badge)) continue;
            if (r.needsYou) anyNeed = true;
            profiles.addAll(r.profiles);
        }
        if (profile != null && !profiles.contains(profile)) profile = null;
        if (!anyNeed && needsYou) needsYou = false;
        if (!anyNeed && profiles.isEmpty()) {
            refineScroll.setVisibility(View.GONE);
            return;
        }
        refineScroll.setVisibility(View.VISIBLE);
        if (anyNeed) {
            TextView t = Ui.bold(host, "Needs you", 13, needsYou ? R.color.accent : R.color.text_secondary);
            Ui.iconStart(t, R.drawable.ic_triangle_alert, needsYou ? R.color.accent : R.color.warn, 14, 6);
            styleRefine(t, "needs");
            t.setOnClickListener(v -> {
                needsYou = !needsYou;
                renderRefine();
                renderList(-1);
                View again = refineBar.findViewWithTag("needs");
                if (again != null) again.requestFocus();
            });
            refineBar.addView(t);
        }
        for (String p : profiles) {
            boolean on = p.equals(profile);
            TextView t = Ui.bold(host, p, 13, on ? R.color.accent : R.color.text_secondary);
            styleRefine(t, "profile:" + p);
            t.setOnClickListener(v -> {
                profile = on ? null : p;
                renderRefine();
                renderList(-1);
                View again = refineBar.findViewWithTag("profile:" + p);
                if (again != null) again.requestFocus();
            });
            refineBar.addView(t);
        }
    }

    private void styleRefine(TextView t, String tag) {
        styleChip(t, tag);
        t.setPadding(Ui.dp(host, 14), Ui.dp(host, 6), Ui.dp(host, 14), Ui.dp(host, 6));
        t.setMinHeight(Ui.dp(host, 36));
    }

    /** Not a logo (those are trademarks) but what the console's games came on. */
    private static int familyIcon(String badge) {
        if (badge == null) return R.drawable.ic_gamepad_2;
        switch (badge) {
            case "GC": case "WII": case "PS1": case "PS2": case "PS3": case "PSP": case "DC":
                return R.drawable.ic_disc;
            case "SW": case "DS": case "3DS": case "VITA":
                return R.drawable.ic_mark;
            default:
                return R.drawable.ic_gamepad_2;
        }
    }

    /** The chips in order: All first, then each console present. */
    private List<String> chipBadges() {
        Set<String> present = new HashSet<>();
        for (Row r : all) present.add(r.badge);
        List<String> badges = new ArrayList<>(present);
        badges.sort((a, b) -> Integer.compare(Consoles.rank(a), Consoles.rank(b)));
        badges.add(0, null);
        return badges;
    }

    /** L2 / R2 on the list: previous / next console, wrapping through All. */
    private void cycle(int step) {
        List<String> badges = chipBadges();
        int at = badges.indexOf(filter);
        int next = ((at + step) % badges.size() + badges.size()) % badges.size();
        filter = badges.get(next);
        renderChips();
        renderList(-1);
        View again = chipBar.findViewWithTag(filter == null ? "" : filter);
        if (again != null) {
            again.getParent().requestChildFocus(again, again);
            View f = host.getCurrentFocus();
            // Keep the cursor where it was (a row or a chip); only the filter changes.
            if (f == null || chipBar.findViewWithTag(f.getTag()) != null) Ui.focus(again, true);
        }
    }

    @Override void onL2() {
        if (open != null) cycleProfile(-1);
        else cycle(-1);
    }

    @Override void onR2() {
        if (open != null) cycleProfile(1);
        else cycle(1);
    }

    // ---- search ----

    /** Y on the list, and the Search chip. */
    @Override void help() {
        if (open == null) openSearch();
    }

    private void openSearch() {
        showInputSheet("Search games", "Game or profile name. Clear the text to show everything.",
                query, "Cancel", "Search", q -> {
                    query = q;
                    renderChips();
                    renderList(-1);
                    if (!shown.isEmpty()) focusRow(0);
                    else Ui.focus(chipBar.findViewWithTag("search"), host.keyDriven());
                });
    }

    private boolean refined() {
        return !query.isEmpty() || needsYou || profile != null;
    }

    private void clearRefinements() {
        query = "";
        needsYou = false;
        profile = null;
        renderChips();
        renderList(0);
    }

    private int focusedPosition() {
        if (list == null) return -1;
        View f = list.getFocusedChild();
        return f == null ? -1 : list.getChildAdapterPosition(f);
    }

    private void renderList(int refocus) {
        shown.clear();
        for (Row r : all) {
            if (filter != null && !filter.equals(r.badge)) continue;
            if (needsYou && !r.needsYou) continue;
            if (profile != null && !r.profiles.contains(profile)) continue;
            if (!GameFilter.matches(r.name, String.join(" ", r.profiles), query)) continue;
            shown.add(r);
        }
        int games = 0, locked = 0;
        for (Row r : shown) {
            if (!r.entries.isEmpty()) games++;
            else locked++;
        }
        StringBuilder s = new StringBuilder();
        s.append(games).append(games == 1 ? " game" : " games");
        if (locked > 0) s.append(" · ").append(locked).append(locked == 1 ? " folder" : " folders")
                .append(" locked");
        if (filter != null) s.append(" · ").append(Consoles.name(filter));
        if (!query.isEmpty()) s.append(" · “").append(query).append("”");
        if (needsYou) s.append(" · needs you");
        if (profile != null) s.append(" · ").append(profile);
        if (model != null && model.historyPending) {
            s.append(" · reading backup history…");
        } else if (model != null && model.storeError != null) {
            String where = model.input.where == Safety.Where.DRIVE ? "Google Drive could not be reached"
                    : "the backups could not be read";
            long asOf = model.newestIndexedMs();
            if (asOf > 0) {
                s.append(" · history as of ").append(When.format(asOf, model.input.nowMs))
                        .append(", ").append(where);
            } else {
                s.append(" · ").append(where).append(", so history is unknown");
            }
        }
        subtitleBase = s.toString();
        renderSubtitle();
        list.setAdapter(new Adapter());
        if (refocus >= 0) focusRow(Math.min(refocus, shown.size() - 1));
    }

    private String subtitleBase = "";

    private void renderSubtitle() {
        String s = subtitleBase;
        if (model != null && model.historyPending && loadingNote != null) {
            s = s.replace(" · reading backup history…", " · " + loadingNote);
        }
        subtitle.setText(s);
    }

    private void focusRow(int pos) {
        Ui.focusRow(list, pos, host.keyDriven());
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            return new Holder(rowView());
        }

        @Override public void onBindViewHolder(Holder h, int position) {
            Row r = shown.get(position);
            h.badge.setText(r.badge);
            int tint = Consoles.tint(r.badge);
            h.badge.setTextColor(tint);
            ((android.graphics.drawable.GradientDrawable) h.badge.getBackground())
                    .setColor((tint & 0x00FFFFFF) | 0x2E000000);
            h.name.setText(r.name);
            h.meta.setText(r.meta);
            h.status.setText(r.status);
            h.status.setTextColor(Ui.color(host, r.statusColor));
            h.thumb.setVisibility(r.icon == null ? View.GONE : View.VISIBLE);
            art.load(h.thumb, r.icon, Ui.dp(host, 88));
            h.itemView.setOnClickListener(v -> openDetail(h.getBindingAdapterPosition()));
        }

        @Override public int getItemCount() {
            return shown.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView badge, name, meta, status;

        Holder(View v) {
            super(v);
            thumb = (ImageView) ((ViewGroup) v).getChildAt(0);
            badge = (TextView) ((ViewGroup) v).getChildAt(1);
            LinearLayout mid = (LinearLayout) ((ViewGroup) v).getChildAt(2);
            name = (TextView) mid.getChildAt(0);
            meta = (TextView) mid.getChildAt(1);
            status = (TextView) ((ViewGroup) v).getChildAt(3);
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

        ImageView thumb = Ui.image(c, 8);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44));
        tlp.rightMargin = Ui.dp(c, 12);
        thumb.setVisibility(View.GONE);
        row.addView(thumb, tlp);

        row.addView(Ui.badge(c, "", 0xFF9AA4B2));

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

    // ---- game page ----

    private void openDetail(int position) {
        if (position < 0 || position >= shown.size()) return;
        Row r = shown.get(position);
        if (r.entries.isEmpty()) {
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
        // The list may have re-sorted while the page was open (history arrives, a backup ran),
        // so the row is found again by what it is, not by where it was.
        int at = openPosition;
        if (open != null) {
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).key().equals(open.key())) at = i;
            }
        }
        open = null;
        if (listView.getParent() == null) root.addView(listView);
        host.refreshLegend();
        Ui.focusRow(list, at, true);
    }

    private void refreshDetail() {
        if (open == null || root == null) return;
        View old = detailView;
        detailView = buildDetail(open);
        if (old != null) root.removeView(old);
        root.addView(detailView);
    }

    /** L2 / R2 on the page: previous / next profile this game is saved for. */
    private void cycleProfile(int step) {
        if (open == null || open.entries.size() < 2) return;
        int n = open.entries.size();
        selectProfile(((open.selected + step) % n + n) % n);
    }

    private void selectProfile(int index) {
        if (open == null || index == open.selected) return;
        open.selected = index;
        refreshDetail();
        host.refreshLegend();
        host.focusByDefault(backupButton);
        Ui.focus(backupButton, host.keyDriven());
    }

    private View buildDetail(Row r) {
        ShellActivity c = host;
        GameHistory.Entry e = r.entry();
        boolean tall = c.isTall();
        TargetRegistry reg = model.scan.registry;
        Target t = reg.target(r.targetId);
        Emulator em = reg.emulatorOf(r.targetId);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int gx = Ui.dp(c, tall ? 20 : 32), gy = Ui.dp(c, tall ? 16 : 20);
        col.setPadding(gx, gy, gx, 0);

        // The hero costs height, and on the wide form height is what the timeline needs. There
        // it sits to the right of the title block instead, the width of two buttons; on the
        // tall form it is a banner above the title.
        LinearLayout above = col;
        if (r.hero != null && tall) {
            col.addView(hero(r.hero), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 120)));
        } else if (r.hero != null) {
            LinearLayout split = new LinearLayout(c);
            split.setOrientation(LinearLayout.HORIZONTAL);
            above = new LinearLayout(c);
            above.setOrientation(LinearLayout.VERTICAL);
            split.addView(above, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(c, 300), Ui.dp(c, 150));
            hlp.leftMargin = Ui.dp(c, 24);
            split.addView(hero(r.hero), hlp);
            col.addView(split);
        }

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.badge(c, r.badge, Consoles.tint(r.badge)));
        TextView name = Ui.bold(c, r.name, 24, R.color.text_primary);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.dp(c, 12);
        head.addView(name, nlp);
        above.addView(head, top(r.hero != null && tall ? 16 : 0));

        StringBuilder meta = new StringBuilder(shortLabel(em)).append(" · ")
                .append(Sizes.human(e.group.bytes)).append(" · kept in ").append(t.label);
        if (e.group.profileKey != null && r.entries.size() == 1) {
            meta.append(" · profile ").append(profileName(e.group.profileKey));
        }
        above.addView(Ui.text(c, meta.toString(), 14, R.color.text_secondary), top(6));

        if (r.entries.size() > 1) {
            // One chip per profile. The page below is the selected one's: its files, its
            // history, its restore. L2/R2 step through them without leaving the button.
            HorizontalScrollView sv = new HorizontalScrollView(c);
            sv.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(c);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < r.entries.size(); i++) {
                final int index = i;
                boolean on = i == r.selected;
                TextView chip = Ui.bold(c, r.profiles.get(i), 13, on ? R.color.accent : R.color.text_secondary);
                styleRefine(chip, "profile:" + i);
                chip.setOnClickListener(v -> selectProfile(index));
                chips.addView(chip);
            }
            sv.addView(chips);
            above.addView(sv, top(10));
        }

        Status st = statusOf(e, model);
        above.addView(Ui.text(c, st.text, 14, st.hue), top(r.entries.size() > 1 ? 8 : 2));

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
        if (e.onDevice) {
            StringBuilder now = new StringBuilder("On the device now: ").append(count(e.group.files.size(), "file"))
                    .append(", ").append(Sizes.human(e.group.bytes)).append(".");
            // Which backups differ is the question; say plainly when the newer ones do not.
            long newestBackup = model.newestIndexedMs();
            if (!e.snapshots.isEmpty() && newestBackup > e.snapshots.get(0).atMs) {
                now.append(" Every backup since ").append(When.format(e.snapshots.get(0).atMs, model.input.nowMs))
                        .append(" holds the same files for this game.");
            }
            col.addView(Ui.text(c, now.toString(), 14, R.color.text_secondary), top(6));
        }
        if (moments.isEmpty()) {
            col.addView(Ui.text(c, model.historyPending ? "Reading backup history…"
                    : e.onDevice ? "No backup holds this game yet. Back up now and it will be here."
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

    /** The wide scrape behind the page's head, fading into the window at its foot. */
    private View hero(File src) {
        ShellActivity c = host;
        FrameLayout box = new FrameLayout(c);
        ImageView img = Ui.image(c, 14);
        box.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View fade = new View(c);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x00000000, Ui.color(c, R.color.ink_black) });
        g.setCornerRadius(Ui.dp(c, 14));
        fade.setBackground(g);
        box.addView(fade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        art.load(img, src, Ui.dp(c, 640));
        return box;
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
            String holds = s.files == 0 ? "nothing left"
                    : count(s.files, "file") + " · " + Sizes.human(s.bytes);
            out.add(new Moment(s.versionId, noteOf(s), holds, s.atMs, false));
        }
        for (String vid : e.safetyCopies) {
            long at = 0;
            for (IndexEntry ie : model.index) if (ie.id.equals(vid)) at = ie.createdAtMs;
            out.add(new Moment(vid, "Safety copy, made before a restore", "", at, true));
        }
        out.sort((a, b) -> Long.compare(b.atMs, a.atMs));
        return out;
    }

    /**
     * What changed in this backup, file by file when there are one or two, counted beyond
     * that. Files that vanished are named in amber: that is the line a person looking for a
     * lost save needs to find.
     */
    private CharSequence noteOf(GameHistory.Snapshot s) {
        if (s.first) return "Full save";
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (s.updated > 0) b.append(named(s.updatedPaths, "updated"));
        if (s.added > 0) {
            if (b.length() > 0) b.append(" · ");
            b.append(named(s.addedPaths, "added"));
        }
        if (s.removed > 0) {
            if (b.length() > 0) b.append(" · ");
            int start = b.length();
            b.append(count(s.removed, "file")).append(" gone");
            if (s.removed <= 2) b.append(": ").append(basenames(s.removedPaths));
            b.setSpan(new ForegroundColorSpan(Ui.color(host, R.color.warn)), start, b.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (s.looksReset) {
            int start = b.length();
            b.append(" · ").append(Sizes.human(s.shrunkBytes))
                    .append(" smaller; looks like a new save, the copy before is kept");
            b.setSpan(new ForegroundColorSpan(Ui.color(host, R.color.warn)), start, b.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    private static String named(List<String> paths, String verb) {
        if (paths.size() <= 2) return basenames(paths) + " " + verb;
        return count(paths.size(), "file") + " " + verb;
    }

    private static String basenames(List<String> paths) {
        List<String> out = new ArrayList<>();
        for (String p : paths) {
            int cut = p.lastIndexOf('/');
            out.add(cut < 0 ? p : p.substring(cut + 1));
        }
        return String.join(", ", out);
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
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
            v.setOrientation(LinearLayout.HORIZONTAL);
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setBackgroundResource(R.drawable.focus_ring);
            v.setPadding(Ui.dp(c, 18), Ui.dp(c, 12), Ui.dp(c, 18), Ui.dp(c, 12));
            v.setMinimumHeight(Ui.dp(c, 60));
            v.setFocusable(true);
            v.setClickable(true);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(c, 10);
            v.setLayoutParams(lp);
            LinearLayout left = new LinearLayout(c);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(Ui.text(c, "", 17, R.color.text_primary));
            left.addView(Ui.text(c, "", 14, R.color.text_secondary));
            v.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView holds = Ui.text(c, "", 14, R.color.text_secondary);
            holds.setGravity(Gravity.END);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.leftMargin = Ui.dp(c, 12);
            v.addView(holds, hlp);
            return new MomentHolder(v);
        }

        @Override public void onBindViewHolder(MomentHolder h, int position) {
            Moment m = items.get(position);
            h.when.setText(When.format(m.atMs, model.input.nowMs));
            h.note.setText(m.note);
            h.holds.setText(m.holds);
            h.itemView.setOnClickListener(v -> confirmRestore(row, m));
        }

        @Override public int getItemCount() {
            return items.size();
        }
    }

    private static final class MomentHolder extends RecyclerView.ViewHolder {
        final TextView when, note, holds;

        MomentHolder(LinearLayout v) {
            super(v);
            LinearLayout left = (LinearLayout) v.getChildAt(0);
            when = (TextView) left.getChildAt(0);
            note = (TextView) left.getChildAt(1);
            holds = (TextView) v.getChildAt(1);
        }
    }

    // ---- put back ----

    /**
     * A on a moment. The sheet comes up at once and says what is happening, then turns into
     * the question. The work runs on the shell's second executor, never behind a store read,
     * and is dropped if the person closes the sheet (B) before it is done.
     */
    private void confirmRestore(Row row, Moment m) {
        GameHistory.Entry e = row.entry();
        String when = When.format(m.atMs, model.input.nowMs);
        TargetRegistry reg = model.scan.registry;
        final int ticket = ++compareTicket;
        showSheet("Comparing…", "Reading the backup from " + when + "…", null, "Cancel", null);
        HomeModel.Note note = what -> host.ui().post(() -> {
            SheetView sheet = sheet();
            if (ticket == compareTicket && sheet != null) sheet.setBody(what);
        });
        host.work().execute(() -> {
            RestoreSession session = null;
            String failure = null;
            try {
                BackupSink store = Stores.forVersion(host, m.versionId);
                Manifest manifest = new ManifestCache(host).get(store, m.versionId);
                SaveGroup stored = GameHistory.groupsOf(reg, manifest).get(e.key);
                // The filter is the game's files then and now, so a file added since shows
                // up as left alone rather than vanishing from the preview.
                Set<String> paths = new HashSet<>();
                if (stored != null) for (FileStat f : stored.files) paths.add(f.path);
                if (e.onDevice) for (FileStat f : e.group.files) paths.add(f.path);
                note.say("Checking " + count(paths.size(), "file") + " of " + row.name + " on this device…");
                session = RestoreSession.load(host, m.versionId,
                        Collections.singletonMap(e.targetId, paths), note);
            } catch (Exception ex) {
                failure = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            final RestoreSession s = session;
            final String err = failure;
            host.ui().post(() -> {
                if (host.isFinishing() || host.isDestroyed() || open != row) return;
                // Closed the sheet, or asked again: this answer is to a question no longer asked.
                if (ticket != compareTicket || sheet() == null) return;
                showRestoreSheet(row, m, when, s, err);
            });
        });
    }

    private int compareTicket;

    private void showRestoreSheet(Row row, Moment m, String when, RestoreSession s, String err) {
        GameHistory.Entry e = row.entry();
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
        String profile = e.group.profileKey == null ? null : profileName(e.group.profileKey);
        RestoreSummary sum = RestoreSummary.describe(plan, row.name, profile, when, shortLabel(em),
                model.input.nowMs, m.safety);
        if (sum.alreadyThere) {
            showSheet(sum.title, sum.body, null, sum.primary, null);
            return;
        }
        String body = sum.body;
        if (!e.onDevice && e.group.profileKey != null) {
            body += "\n\nThis profile is not on the device any more. " + shortLabel(em)
                    + " may not show the save until the profile exists again.";
        }
        Runnable primary = () -> host.startRestore(
                new BackupService.RestoreRequest(m.versionId, Collections.singletonList(sum.primaryPlan)));
        Runnable secondary = sum.secondaryPlan == null ? null : () -> host.startRestore(
                new BackupService.RestoreRequest(m.versionId, Collections.singletonList(sum.secondaryPlan)));
        showSheet(sum.title, body, sum.secondary, sum.primary, primary, secondary);
    }

    // ---- profile rename ----

    private void rename() {
        GameHistory.Entry e = open == null ? null : open.entry();
        if (e == null || e.group.profileKey == null) return;
        String uuid = e.group.profileKey;
        showInputSheet("Rename this profile", "Only EmuBackup uses this name.",
                profileName(uuid), "Cancel", "Save", name -> {
                    if (name.isEmpty()) return;
                    aliases.set(uuid, name);
                    onModel(model);
                });
    }

    // ---- pane contract ----

    @Override String[] legend() {
        if (open == null) return new String[] { "A", "Open", "B", "Back", "Y", "Search", "L2/R2", "Console" };
        List<String> l = new ArrayList<>(List.of("A", "Select", "B", "Back"));
        GameHistory.Entry e = open.entry();
        if (e != null && e.group.profileKey != null) {
            l.add("X");
            l.add("Rename profile");
        }
        if (open.entries.size() > 1) {
            l.add("L2/R2");
            l.add("Profile");
        }
        return l.toArray(new String[0]);
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
        if (refined()) {
            // One level: the search and the refinements go first, then the rail.
            clearRefinements();
            return true;
        }
        return false;
    }
}
